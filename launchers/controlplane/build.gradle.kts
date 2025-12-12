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

plugins {
    id("application")
    alias(libs.plugins.shadow)
    alias(libs.plugins.docker)
}

dependencies {

    runtimeOnly(libs.edcv.bom.controlplane)
    runtimeOnly(libs.edcv.bom.controlplane.sql)
    runtimeOnly(libs.edcv.bom.controlplane.nats)
    runtimeOnly(libs.edcv.bom.controlplane.nats.cdc)
    runtimeOnly(libs.edcv.bom.controlplane.dcp)

    runtimeOnly(libs.edc.core.edrstore)
    runtimeOnly(libs.edc.vault.hashicorp)
    runtimeOnly(libs.edc.edrstore.receiver)

    runtimeOnly(libs.bouncyCastle.bcprovJdk18on)

    runtimeOnly(project(":extensions:api:mgmt"))
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
