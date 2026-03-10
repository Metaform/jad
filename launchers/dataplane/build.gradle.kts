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
    `java-library`
    id("application")
    alias(libs.plugins.shadow)
}

dependencies {
    runtimeOnly(libs.tink)
    implementation(libs.edc.bom.dataplane) {
        exclude("org.eclipse.edc", "data-plane-self-registration")
    }
    runtimeOnly(project(":extensions:data-plane-public-api-v2"))
    runtimeOnly(project(":extensions:data-plane-certs"))

    runtimeOnly(libs.edc.core.participantcontext.config)
    runtimeOnly(libs.edc.vault.hashicorp)
    runtimeOnly(libs.edc.bom.dataplane.sql)
}

tasks.shadowJar {
    mergeServiceFiles()
    archiveFileName.set("${project.name}.jar")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

application {
    mainClass.set("org.eclipse.edc.boot.system.runtime.BaseRuntime")
}

edcBuild {
    publish.set(false)
}