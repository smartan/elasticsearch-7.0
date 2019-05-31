/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.gradle.test

import org.elasticsearch.gradle.VersionProperties
import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionAdapter
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskState
import org.gradle.api.tasks.options.Option
import org.gradle.api.tasks.testing.Test
import org.gradle.plugins.ide.idea.IdeaPlugin
import org.gradle.process.CommandLineArgumentProvider

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.stream.Stream

/**
 * A wrapper task around setting up a cluster and running rest tests.
 */
class RestIntegTestTask extends DefaultTask {

    private static final Logger LOGGER = Logging.getLogger(RestIntegTestTask)

    protected ClusterConfiguration clusterConfig

    protected Test runner

    protected Task clusterInit

    /** Info about nodes in the integ test cluster. Note this is *not* available until runtime. */
    List<NodeInfo> nodes

    /** Flag indicating whether the rest tests in the rest spec should be run. */
    @Input
    Boolean includePackaged = false

    RestIntegTestTask() {
        runner = project.tasks.create("${name}Runner", Test.class)
        super.dependsOn(runner)
        clusterInit = project.tasks.create(name: "${name}Cluster#init", dependsOn: project.testClasses)
        runner.dependsOn(clusterInit)
        clusterConfig = project.extensions.create("${name}Cluster", ClusterConfiguration.class, project)

        // disable the build cache for rest test tasks
        // there are a number of inputs we aren't properly tracking here so we'll just not cache these for now
        runner.outputs.doNotCacheIf('Caching is disabled for REST integration tests') { true }

        // override/add more for rest tests
        runner.maxParallelForks = 1
        runner.include('**/*IT.class')
        runner.systemProperty('tests.rest.load_packaged', 'false')

        /*
         * We use lazy-evaluated strings in order to configure system properties whose value will not be known until
         * execution time (e.g. cluster port numbers). Adding these via the normal DSL doesn't work as these get treated
         * as task inputs and therefore Gradle attempts to snapshot them before/after task execution. This fails due
         * to the GStrings containing references to non-serializable objects.
         *
         * We bypass this by instead passing this system properties vi a CommandLineArgumentProvider. This has the added
         * side-effect that these properties are NOT treated as inputs, therefore they don't influence things like the
         * build cache key or up to date checking.
         */
        def nonInputProperties = new CommandLineArgumentProvider() {
            private final Map<String, Object> systemProperties = [:]

            void systemProperty(String key, Object value) {
                systemProperties.put(key, value)
            }

            @Override
            Iterable<String> asArguments() {
                return systemProperties.collect { key, value ->
                    "-D${key}=${value.toString()}".toString()
                }
            }
        }
        runner.jvmArgumentProviders.add(nonInputProperties)
        runner.ext.nonInputProperties = nonInputProperties

        if (System.getProperty("tests.rest.cluster") == null) {
            if (System.getProperty("tests.cluster") != null) {
                throw new IllegalArgumentException("tests.rest.cluster and tests.cluster must both be null or non-null")
            }
            // we pass all nodes to the rest cluster to allow the clients to round-robin between them
            // this is more realistic than just talking to a single node
            nonInputProperties.systemProperty('tests.rest.cluster', "${-> nodes.collect { it.httpUri() }.join(",")}")
            nonInputProperties.systemProperty('tests.config.dir', "${-> nodes[0].pathConf}")
            // TODO: our "client" qa tests currently use the rest-test plugin. instead they should have their own plugin
            // that sets up the test cluster and passes this transport uri instead of http uri. Until then, we pass
            // both as separate sysprops
            nonInputProperties.systemProperty('tests.cluster', "${-> nodes[0].transportUri()}")

            // dump errors and warnings from cluster log on failure
            TaskExecutionAdapter logDumpListener = new TaskExecutionAdapter() {
                @Override
                void afterExecute(Task task, TaskState state) {
                    if (task == runner && state.failure != null) {
                        for (NodeInfo nodeInfo : nodes) {
                            printLogExcerpt(nodeInfo)
                        }
                    }
                }
            }
            runner.doFirst {
                project.gradle.addListener(logDumpListener)
            }
            runner.doLast {
                project.gradle.removeListener(logDumpListener)
            }
        } else {
            if (System.getProperty("tests.cluster") == null) {
                throw new IllegalArgumentException("tests.rest.cluster and tests.cluster must both be null or non-null")
            }
            // an external cluster was specified and all responsibility for cluster configuration is taken by the user
            runner.systemProperty('tests.rest.cluster', System.getProperty("tests.rest.cluster"))
            runner.systemProperty('test.cluster', System.getProperty("tests.cluster"))
        }

        // copy the rest spec/tests into the test resources
        Task copyRestSpec = createCopyRestSpecTask()
        runner.dependsOn(copyRestSpec)
        
        // this must run after all projects have been configured, so we know any project
        // references can be accessed as a fully configured
        project.gradle.projectsEvaluated {
            if (enabled == false) {
                runner.enabled = false
                clusterInit.enabled = false
                return // no need to add cluster formation tasks if the task won't run!
            }
            // only create the cluster if needed as otherwise an external cluster to use was specified
            if (System.getProperty("tests.rest.cluster") == null) {
                nodes = ClusterFormationTasks.setup(project, "${name}Cluster", runner, clusterConfig)
            }
            super.dependsOn(runner.finalizedBy)
        }
    }

    /** Sets the includePackaged property */
    public void includePackaged(boolean include) {
        includePackaged = include
    }

    @Option(
        option = "debug-jvm",
        description = "Enable debugging configuration, to allow attaching a debugger to elasticsearch."
    )
    public void setDebug(boolean enabled) {
        clusterConfig.debug = enabled;
    }

    public List<NodeInfo> getNodes() {
        return nodes
    }

    @Override
    public Task dependsOn(Object... dependencies) {
        runner.dependsOn(dependencies)
        for (Object dependency : dependencies) {
            if (dependency instanceof Fixture) {
                runner.finalizedBy(((Fixture)dependency).getStopTask())
            }
        }
        return this
    }

    @Override
    public void setDependsOn(Iterable<?> dependencies) {
        runner.setDependsOn(dependencies)
        for (Object dependency : dependencies) {
            if (dependency instanceof Fixture) {
                runner.finalizedBy(((Fixture)dependency).getStopTask())
            }
        }
    }

    @Override
    public Task mustRunAfter(Object... tasks) {
        clusterInit.mustRunAfter(tasks)
    }

    /** Print out an excerpt of the log from the given node. */
    protected static void printLogExcerpt(NodeInfo nodeInfo) {
        File logFile = new File(nodeInfo.homeDir, "logs/${nodeInfo.clusterName}.log")
        LOGGER.lifecycle("\nCluster ${nodeInfo.clusterName} - node ${nodeInfo.nodeNum} log excerpt:")
        LOGGER.lifecycle("(full log at ${logFile})")
        LOGGER.lifecycle('-----------------------------------------')
        Stream<String> stream = Files.lines(logFile.toPath(), StandardCharsets.UTF_8)
        try {
            boolean inStartup = true
            boolean inExcerpt = false
            int linesSkipped = 0
            for (String line : stream) {
                if (line.startsWith("[")) {
                    inExcerpt = false // clear with the next log message
                }
                if (line =~ /(\[WARN *\])|(\[ERROR *\])/) {
                    inExcerpt = true // show warnings and errors
                }
                if (inStartup || inExcerpt) {
                    if (linesSkipped != 0) {
                        LOGGER.lifecycle("... SKIPPED ${linesSkipped} LINES ...")
                    }
                    LOGGER.lifecycle(line)
                    linesSkipped = 0
                } else {
                    ++linesSkipped
                }
                if (line =~ /recovered \[\d+\] indices into cluster_state/) {
                    inStartup = false
                }
            }
        } finally {
            stream.close()
        }
        LOGGER.lifecycle('=========================================')

    }

    /**
     * Creates a task (if necessary) to copy the rest spec files.
     *
     * @param project The project to add the copy task to
     * @param includePackagedTests true if the packaged tests should be copied, false otherwise
     */
    Task createCopyRestSpecTask() {
        project.configurations {
            restSpec
        }
        project.dependencies {
            restSpec "org.elasticsearch:rest-api-spec:${VersionProperties.elasticsearch}"
        }
        Task copyRestSpec = project.tasks.findByName('copyRestSpec')
        if (copyRestSpec != null) {
            return copyRestSpec
        }
        Map copyRestSpecProps = [
                name     : 'copyRestSpec',
                type     : Copy,
                dependsOn: [project.configurations.restSpec, 'processTestResources']
        ]
        copyRestSpec = project.tasks.create(copyRestSpecProps) {
            into project.sourceSets.test.output.resourcesDir
        }
        project.afterEvaluate {
            copyRestSpec.from({ project.zipTree(project.configurations.restSpec.singleFile) }) {
                include 'rest-api-spec/api/**'
                if (includePackaged) {
                    include 'rest-api-spec/test/**'
                }
            }
        }
        if (project.plugins.hasPlugin(IdeaPlugin)) {
            project.idea {
                module {
                    if (scopes.TEST != null) {
                        scopes.TEST.plus.add(project.configurations.restSpec)
                    }
                }
            }
        }
        return copyRestSpec
    }
}
