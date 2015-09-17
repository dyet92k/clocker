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
package brooklyn.entity.mesos.framework.marathon;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.gson.JsonElement;

import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.feed.http.HttpFeed;
import org.apache.brooklyn.feed.http.HttpPollConfig;
import org.apache.brooklyn.feed.http.HttpValueFunctions;
import org.apache.brooklyn.feed.http.JsonFunctions;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.guava.Functionals;

import brooklyn.entity.mesos.MesosUtils;
import brooklyn.entity.mesos.framework.MesosFrameworkImpl;

/**
 * The Marathon framework implementation.
 */
public class MarathonFrameworkImpl extends MesosFrameworkImpl implements MarathonFramework {

    private static final Logger LOG = LoggerFactory.getLogger(MarathonFramework.class);

    private transient HttpFeed httpFeed;

    @Override
    public void init() {
        String marathonUrl = config().get(MARATHON_URL);
        config().set(FRAMEWORK_URL, marathonUrl);

        super.init();
    }

    @Override
    public void start(Collection<? extends Location> locations) {
        sensors().set(SERVICE_UP, Boolean.FALSE);

        super.start(locations);

        connectSensors();
    }

    @Override
    public void stop() {
        disconnectSensors();

        // Stop applications and tasks

        sensors().set(SERVICE_UP, Boolean.FALSE);
    }

    public void connectSensors() {
        HttpFeed.Builder httpFeedBuilder = HttpFeed.builder()
                .entity(this)
                .period(500, TimeUnit.MILLISECONDS)
                .baseUri(sensors().get(FRAMEWORK_URL))
                .poll(new HttpPollConfig<List<String>>(MARATHON_APPLICATIONS)
                        .suburl("/v2/apps/")
                        .onSuccess(Functionals.chain(HttpValueFunctions.jsonContents(), JsonFunctions.walk("apps"), JsonFunctions.forEach(JsonFunctions.<String>getPath("id"))))
                        .onFailureOrException(Functions.constant(Arrays.asList(new String[0]))))
                .poll(new HttpPollConfig<String>(MARATHON_FRAMEWORK_ID)
                        .suburl("/v2/info/")
                        .onSuccess(HttpValueFunctions.jsonContents("frameworkId", String.class))
                        .onFailureOrException(Functions.constant("")))
                .poll(new HttpPollConfig<String>(MARATHON_VERSION)
                        .suburl("/v2/info/")
                        .onSuccess(HttpValueFunctions.jsonContents("version", String.class))
                        .onFailureOrException(Functions.constant("")))
                .poll(new HttpPollConfig<Boolean>(SERVICE_UP)
                        .suburl("/ping")
                        .onSuccess(HttpValueFunctions.responseCodeEquals(200))
                        .onFailureOrException(Functions.constant(Boolean.FALSE)));

        httpFeed = httpFeedBuilder.build();
    }

    public void disconnectSensors() {
        if (httpFeed != null) {
            httpFeed.stop();
        }
    }

    @Override
    public boolean startApplication(String id, String command, String imageName, String imageVersion) throws IOException {
        Map<String, String> substitutions = MutableMap.<String, String>builder()
                .put("applicationId", id)
                .put("command", command)
                .put("imageName", imageName)
                .put("imageVersion", imageVersion)
                .build();
        Optional<String> result = MesosUtils.postJson(sensors().get(FRAMEWORK_URL) + "/v2/apps", "classpath:///brooklyn/entity/mesos/framework/marathon/create-app.json", substitutions);
        if (!result.isPresent()) {
            return false;
        } else {
            LOG.debug("Success creating Marathon task");
            JsonElement json = JsonFunctions.asJson().apply(result.get());
            // set task id and so on
            return true;
        }
    }

}
