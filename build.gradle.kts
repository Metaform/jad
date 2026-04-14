/*
 *  Copyright (c) 2025 Metaform Systems, Inc.
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Metaform Systems, Inc. - initial API and implementation
 *
 */

import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
import java.net.URI

plugins {
    `java-library`
    id("com.bmuschko.docker-remote-api") version "10.0.0"
    id("com.gradleup.shadow") version "9.4.1"
    alias(libs.plugins.edc.build)
}

buildscript {
    dependencies {
        val version: String by project
        classpath("org.eclipse.edc.autodoc:org.eclipse.edc.autodoc.gradle.plugin:$version")
    }
}

val edcBuildId = libs.plugins.edc.build.get().pluginId
val jadVersion: String by project

val downloadOtelAgent by tasks.registering {
    val outputFile = layout.buildDirectory.file("otel/opentelemetry-javaagent.jar")
    outputs.file(outputFile)
    doLast {
        val url = URI("https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar").toURL()
        val destFile = outputFile.get().asFile
        destFile.parentFile.mkdirs()
        url.openStream().use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}

allprojects {
    apply(plugin = edcBuildId)
    apply(plugin = "org.eclipse.edc.autodoc")

    // configure which version of the annotation processor to use. defaults to the same version as the plugin
    configure<org.eclipse.edc.plugins.autodoc.AutodocExtension> {
        outputDirectory.set(project.layout.buildDirectory.asFile)
    }
}
subprojects {
    afterEvaluate {
        if (project.plugins.hasPlugin("com.github.johnrengelman.shadow") &&
            file("${project.projectDir}/src/main/docker/Dockerfile").exists()
        ) {

            //actually apply the plugin to the (sub-)project
            apply(plugin = "com.bmuschko.docker-remote-api")

            val copyOtelAgent = tasks.register<Copy>("copyOtelAgent") {
                dependsOn(rootProject.tasks.named("downloadOtelAgent"))
                from(rootProject.layout.buildDirectory.dir("otel"))
                into(project.layout.buildDirectory.dir("otel"))
            }

            // configure the "dockerize" task
            val dockerTask: DockerBuildImage = tasks.create("dockerize", DockerBuildImage::class) {
                val dockerContextDir = project.projectDir
                dockerFile.set(file("$dockerContextDir/src/main/docker/Dockerfile"))
                images.add("ghcr.io/metaform/jad/${project.name}:${jadVersion}")
                images.add("ghcr.io/metaform/jad/${project.name}:latest")

                //images.add("${project.name}:latest")
                // specify platform with the -Dplatform flag:
                if (System.getProperty("platform") != null)
                    platform.set(System.getProperty("platform"))
                buildArgs.put("JAR", "build/libs/${project.name}.jar")
                buildArgs.put("OTEL_AGENT", "build/otel/opentelemetry-javaagent.jar")
                inputDir.set(file(dockerContextDir))
            }
            dockerTask.dependsOn(tasks.named("shadowJar"))
            dockerTask.dependsOn(copyOtelAgent)
        }
    }
}
