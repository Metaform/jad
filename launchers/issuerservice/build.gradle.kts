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
    implementation(libs.edc.issuance.spi) // for seeding the attestations
    runtimeOnly(libs.edc.bom.issuerservice)
    runtimeOnly(libs.edc.ih.api.did)
    runtimeOnly(libs.edc.ih.api.participants)
    runtimeOnly(libs.edc.vault.hashicorp)
    runtimeOnly(libs.edc.bom.issuerservice.sql)
    runtimeOnly(libs.edc.core.participantcontext.config)
    runtimeOnly(libs.edc.store.participantcontext.config.sql)
}

tasks.shadowJar {
    mergeServiceFiles()
    archiveFileName.set("issuerservice.jar")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

application {
    mainClass.set("org.eclipse.edc.boot.system.runtime.BaseRuntime")
}

edcBuild {
    publish.set(false)
}