/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.plugins.quality.internal

import org.gradle.api.GradleException
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.project.ant.AntLoggingAdapter
import org.gradle.api.internal.project.ant.BasicAntBuilder
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.internal.logging.ConsoleRenderer
import org.gradle.workers.WorkAction
import org.slf4j.Logger
import org.slf4j.LoggerFactory

abstract class CheckstyleAction implements WorkAction<CheckstyleParameters> {
    private static final Logger LOGGER = LoggerFactory.getLogger(CheckstyleAction.class)
    public final static String CONFIG_LOC_PROPERTY = "config_loc";
    private final static String FAILURE_PROPERTY_NAME = 'org.gradle.checkstyle.violations'

    @Override
    void execute() {
        def checkstyleClasspath = parameters.checkstyleClasspath
        def source = parameters.source
        def classpath = parameters.classpath

        def showViolations = parameters.showViolations.get()
        def maxErrors = parameters.maxErrors.get()
        def maxWarnings = parameters.maxWarnings.get()
        def configProperties = parameters.configProperties.get()
        def ignoreFailures = parameters.ignoreFailures.get()

        def config = parameters.configFile.get().asFile
        def configDir = parameters.configDirectory.getAsFile().getOrNull()

        def xmlDestination = parameters.xmlReportFile.get().asFile
        def htmlDestination = parameters.htmlReportFile.getAsFile().getOrNull()

        def ant = new BasicAntBuilder()
        ant.getProject().removeBuildListener(ant.getProject().getBuildListeners().get(0));
        ant.getProject().addBuildListener(new AntLoggingAdapter())

        try {
            try {
                ant.taskdef(name: 'checkstyle', classname: 'com.puppycrawl.tools.checkstyle.CheckStyleTask', classpath: checkstyleClasspath.asPath)
            } catch (RuntimeException ignore) {
                ant.taskdef(name: 'checkstyle', classname: 'com.puppycrawl.tools.checkstyle.ant.CheckstyleAntTask', classpath: checkstyleClasspath.asPath)
            }

            ant.checkstyle(config: config, failOnViolation: false,
                    maxErrors: maxErrors, maxWarnings: maxWarnings,
                    failureProperty: FAILURE_PROPERTY_NAME) {

                source.addToAntBuilder(ant, 'fileset', FileCollection.AntType.FileSet)
                classpath.addToAntBuilder(ant, 'classpath')

                if (showViolations) {
                    formatter(type: 'plain', useFile: false)
                }

                formatter(type: 'xml', toFile: xmlDestination)

                configProperties.each { key, value ->
                    property(key: key, value: value)
                }

                if (configDir) {
                    // Use configDir for config_loc
                    property(key: CONFIG_LOC_PROPERTY, value: configDir.getAbsolutePath())
                }
            }

            if (htmlDestination) {
                def stylesheet = parameters.htmlStylesheetFile.isPresent() ? parameters.htmlStylesheetFile.get().asFile.text :
                        Checkstyle.getClassLoader().getResourceAsStream('checkstyle-noframes-sorted.xsl').text
                ant.xslt(in: xmlDestination, out: htmlDestination) {
                    style {
                        string(value: stylesheet)
                    }
                }
            }

            def reportXml = parseCheckstyleXml(xmlDestination)
            if (ant.project.properties[FAILURE_PROPERTY_NAME] && !ignoreFailures) {
                throw new GradleException(getMessage(htmlDestination, xmlDestination, reportXml))
            } else {
                if (violationsExist(reportXml)) {
                    LOGGER.warn(getMessage(htmlDestination, xmlDestination, reportXml))
                }
            }
        } finally {
            if (ant) {
                ant.close()
            }
        }
    }

    private static boolean violationsExist(Node reportXml) {
        return reportXml != null && getErrorFileCount(reportXml) > 0
    }

    private static parseCheckstyleXml(File xmlDestination) {
        return new XmlParser().parse(xmlDestination)
    }

    private static String getMessage(File htmlDestination, File xmlDestination, Node reportXml) {
        def report = htmlDestination ?: xmlDestination
        return "Checkstyle rule violations were found. See the report at: " +
                new ConsoleRenderer().asClickableFileUrl(report) + " " +
                getViolationMessage(reportXml)
    }

    private static int getErrorFileCount(Node reportXml) {
        return reportXml.file.error.groupBy { it.parent().@name }.keySet().size()
    }

    private static String getViolationMessage(Node reportXml) {
        if (violationsExist(reportXml)) {
            def errorFileCount = getErrorFileCount(reportXml)
            def violations = reportXml.file.error.countBy { it.@severity }
            return """
                    Checkstyle files with violations: $errorFileCount
                    Checkstyle violations by severity: ${violations}
                    """.stripIndent()
        }
        return "\n"
    }
}
