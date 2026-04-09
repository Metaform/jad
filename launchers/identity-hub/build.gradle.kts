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

    runtimeOnly(libs.edc.bom.identityhub)
    runtimeOnly(libs.edc.vault.hashicorp)
    runtimeOnly(libs.edc.bom.identityhub.sql)
    runtimeOnly(libs.edc.core.participantcontext.config)
    runtimeOnly(libs.edc.store.participantcontext.config.sql)
    runtimeOnly(libs.edc.monitor.console)
    runtimeOnly(libs.edc.monitor.otel)

    runtimeOnly(libs.opentelemetry.exporter.otlp)
}

application {
    mainClass.set("org.eclipse.edc.boot.system.runtime.BaseRuntime")
}

tasks.shadowJar {
    mergeServiceFiles()
    archiveFileName.set("${project.name}.jar")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

edcBuild {
    publish.set(false)
}
