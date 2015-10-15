/*
 * Copyright 2014-2015 by Cloudsoft Corporation Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package brooklyn.location.mesos.framework.marathon;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.base.Objects.ToStringHelper;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.location.MachineProvisioningLocation;
import org.apache.brooklyn.api.location.NoMachinesAvailableException;
import org.apache.brooklyn.camp.brooklyn.BrooklynCampConstants;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.location.LocationConfigKeys;
import org.apache.brooklyn.core.location.dynamic.DynamicLocation;
import org.apache.brooklyn.entity.group.DynamicCluster;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.location.jclouds.JcloudsLocationConfig;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.flags.SetFromFlag;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.text.Strings;

import brooklyn.entity.container.DockerAttributes;
import brooklyn.entity.container.docker.DockerContainer;
import brooklyn.entity.mesos.framework.marathon.MarathonFramework;
import brooklyn.entity.mesos.task.MesosTask;
import brooklyn.entity.mesos.task.marathon.MarathonTask;
import brooklyn.location.mesos.framework.MesosFrameworkLocation;
import brooklyn.networking.common.subnet.PortForwarder;

public class MarathonLocation extends MesosFrameworkLocation implements MachineProvisioningLocation<MarathonTaskLocation>,
        DynamicLocation<MarathonFramework, MarathonLocation> {

    private static final long serialVersionUID = -1453203257759956820L;

    private static final Logger LOG = LoggerFactory.getLogger(MarathonLocation.class);

    public static final String TASK_MUTEX = "task";

    private transient ReadWriteLock lock = new ReentrantReadWriteLock();

    @SetFromFlag("portForwarder")
    private PortForwarder portForwarder;

    public MarathonLocation() {
        this(Maps.newLinkedHashMap());
    }

    public MarathonLocation(Map properties) {
        super(properties);

        if (isLegacyConstruction()) {
            init();
        }
    }

    public MarathonTaskLocation obtain() throws NoMachinesAvailableException {
        return obtain(Maps.<String,Object>newLinkedHashMap());
    }

    // Lookup task details from entity
    private Map<Object, Object> getTaskFlags(Entity entity) {
        Map<Object, Object> flags = MutableMap.of();
        Map<String, Object> provisioningProperties = ImmutableMap.copyOf(entity.config().get(SoftwareProcess.PROVISIONING_PROPERTIES));

        // CPU
        Double cpus;
        Integer minCores = entity.config().get(JcloudsLocationConfig.MIN_CORES);
        if (minCores == null) {
            minCores = (Integer) provisioningProperties.get(JcloudsLocationConfig.MIN_CORES.getName());
        }
        if (minCores == null) {
            cpus = entity.config().get(MarathonTask.CPU_RESOURCES);
        } else {
            cpus = 1.0d * minCores;
        }
        flags.put(MarathonTask.CPU_RESOURCES, Optional.fromNullable(cpus).or(0.25d));

        // Memory
        Integer memory = (Integer) entity.config().get(JcloudsLocationConfig.MIN_RAM);
        if (memory == null) {
            memory = (Integer) provisioningProperties.get(JcloudsLocationConfig.MIN_RAM.getName());
        }
        if (memory == null) {
            memory = entity.config().get(MarathonTask.MEMORY_RESOURCES);
        }
        flags.put(MarathonTask.MEMORY_RESOURCES, Optional.fromNullable(memory).or(256));

        // Docker command and args
        String command = entity.config().get(MarathonTask.COMMAND);
        if (command != null) flags.put(MarathonTask.COMMAND, command);
        List<String> args = entity.config().get(MarathonTask.ARGS);
        flags.put(MarathonTask.ARGS, args);

        return flags;
    }

    @Override
    public MarathonTaskLocation obtain(Map<?,?> flags) throws NoMachinesAvailableException {
        lock.readLock().lock();
        try {
            // Lookup entity from context or flags
            Object context = flags.get(LocationConfigKeys.CALLER_CONTEXT.getName());
            if (context == null || !(context instanceof Entity)) {
                throw new IllegalStateException("Invalid location context: " + context);
            }
            Entity entity = (Entity) context;

            // Check if entity type is supported
            if (!isSupported(entity)) {
                LOG.warn("Tried to start unsupported entity in Marathon location: {}", entity);
                throw new NoMachinesAvailableException("Unsupported entity type");
            }

            // Start a new task with flags from entity
            String imageName = entity.config().get(DockerContainer.DOCKER_IMAGE_NAME);
            String imageVersion = Optional.fromNullable(entity.config().get(DockerContainer.DOCKER_IMAGE_TAG)).or("latest");
            String name = Optional.fromNullable(entity.config().get(BrooklynCampConstants.PLAN_ID)).or(entity.getId());
            List<Integer> openPorts = entity.config().get(DockerAttributes.DOCKER_OPEN_PORTS);
            List<Integer> directPorts = entity.config().get(DockerAttributes.DOCKER_DIRECT_PORTS);
            Map<Integer, Integer> portBindings = entity.config().get(DockerAttributes.DOCKER_PORT_BINDINGS);
            Map<String, Object> env = entity.config().get(DockerContainer.DOCKER_CONTAINER_ENVIRONMENT);

            Map<Object, Object> taskFlags = MutableMap.builder()
                    .putAll(flags)
                    .put("entity", entity)
                    .put(MarathonTask.DOCKER_OPEN_PORTS, openPorts)
                    .put(MarathonTask.DOCKER_DIRECT_PORTS, directPorts)
                    .put(MarathonTask.DOCKER_PORT_BINDINGS, portBindings)
                    .put(MarathonTask.DOCKER_CONTAINER_ENVIRONMENT, env)
                    .put(MesosTask.TASK_NAME, Strings.toLowerCase(name))
                    .put(MesosTask.MESOS_CLUSTER, getOwner().getMesosCluster())
                    .put(MesosTask.FRAMEWORK, getOwner())
                    .put(MesosTask.MANAGED, Boolean.TRUE)
                    .put(MarathonTask.DOCKER_IMAGE_NAME, imageName)
                    .put(MarathonTask.DOCKER_IMAGE_TAG, imageVersion)
                    .putAll(getTaskFlags(entity))
                    .build();
            LOG.info("Starting task with image {}:{} on framework {}", new Object[] { imageName, imageVersion, framework });
            LOG.debug("Task flags are: {}", Joiner.on(",").withKeyValueSeparator("=").useForNull("").join(taskFlags));
            DynamicCluster cluster = framework.sensors().get(MarathonFramework.MARATHON_TASK_CLUSTER);
            Entity added = cluster.addNode(this, taskFlags);
            if (added == null) {
                throw new NoMachinesAvailableException("Failed to create marathon task");
            } else {
                Entities.invokeEffector((EntityLocal) entity, added, Startable.START,  MutableMap.of("locations", ImmutableList.of(this))).getUnchecked();
            }
            MarathonTask marathonTask = (MarathonTask) added;

            return marathonTask.getDynamicLocation();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void release(MarathonTaskLocation location) {
        lock.readLock().lock();
        try {
            LOG.info("Releasing {}", location);

            DynamicCluster cluster = framework.sensors().get(MarathonFramework.MARATHON_TASK_CLUSTER);
            MarathonTask task = location.getOwner();
            if (cluster.removeMember(task)) {
                LOG.info("Marathon framework {}: member {} released", framework, location);
            } else {
                LOG.warn("Marathon framework {}: member {} not found for release", framework, location);
            }

            // Now close and unmange the container
            try {
                location.close();
                task.stop();
            } catch (Exception e) {
                LOG.warn("Error stopping task: " + task, e);
                Exceptions.propagateIfFatal(e);
            } finally {
                Entities.unmanage(task);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Map<String, Object> getProvisioningFlags(Collection<String> tags) {
        return MutableMap.of();
    }

    @Override
    public MarathonFramework getOwner() {
        return (MarathonFramework) framework;
    }

    @Override
    public MachineProvisioningLocation<MarathonTaskLocation> newSubLocation(Map<?, ?> newFlags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() throws IOException {
        LOG.info("Close called on Marathon framework {}: {}", framework, this);
        try {
            framework.stop(); // XXX check this
        } catch (Exception e) {
            LOG.info("{}: Closing Marathon framework: {}", e.getMessage(), this);
            throw Exceptions.propagate(e);
        } finally {
            LOG.info("Marathon framework closed: {}", this);
        }
    }

    public Lock getLock() {
        return lock.writeLock();
    }

    @Override
    public ToStringHelper string() {
        return super.string().add("framework", framework);
    }

}
