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
import com.github.jengelman.gradle.plugins.shadow.ShadowJavaPlugin
import org.gradle.internal.impldep.org.junit.experimental.categories.Categories.CategoryFilter.exclude

plugins {
    id("application")
    alias(libs.plugins.shadow)
    alias(libs.plugins.docker)
}

dependencies {
    runtimeOnly(libs.edcv.core.connector)
    runtimeOnly(libs.edcv.core.negotiationmanager)
    runtimeOnly(libs.edcv.core.transfermanager)
    runtimeOnly(libs.edcv.banner)
    runtimeOnly(libs.edcv.protocols.dsp)
    runtimeOnly(libs.edc.core.connector)
    runtimeOnly(libs.edc.core.runtime)
    runtimeOnly(libs.edc.core.token)
    runtimeOnly(libs.edc.core.jersey)
    runtimeOnly(libs.edc.core.jetty)
    runtimeOnly(libs.edc.api.observability)
    runtimeOnly(libs.bundles.dcp)
    runtimeOnly(libs.edc.core.controlplane) {
        exclude("org.eclipse.edc", "control-plane-contract-manager")
        exclude("org.eclipse.edc", "control-plane-transfer-manager")
    }
    runtimeOnly(libs.edc.core.dataplane.selector)
    runtimeOnly(libs.edc.core.dataplane.signaling.client)
    runtimeOnly(libs.edc.core.dataplane.signaling.transfer)

    runtimeOnly(libs.edc.vault.hashicorp)
    runtimeOnly(libs.edc.bom.controlplane.sql)
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    exclude("**/pom.properties", "**/pom.xm")
    mergeServiceFiles()
    archiveFileName.set("${project.name}.jar")
}

application {
    mainClass.set("org.eclipse.edc.boot.system.runtime.BaseRuntime")
}

edcBuild {
    publish.set(false)
}
