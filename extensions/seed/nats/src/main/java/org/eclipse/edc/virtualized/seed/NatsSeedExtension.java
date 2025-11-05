/*
 *  Copyright (c) 2024 Metaform Systems, Inc.
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

package org.eclipse.edc.virtualized.seed;

import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.sql.bootstrapper.SqlSchemaBootstrapper;

public class NatsSeedExtension implements ServiceExtension {
    public static final String NAME = "NATS Stream Seed Extension";
    private JetStreamManagement jsm;

    @Setting(key = "edc.nats.cn.subscriber.url")
    private String natsUrl;
    @Setting(description = "The datasource to be used", defaultValue = "default", key = "edc.sql.store.asset.datasource")
    private String dataSourceName;
    @Inject
    private SqlSchemaBootstrapper sqlSchemaBootstrapper;

    @Override
    public String name() {
        return NAME;
    }


    @Override
    public void initialize(ServiceExtensionContext context) {

        sqlSchemaBootstrapper.addStatementFromResource(dataSourceName, "enable_replication.sql");

        try (var conn = Nats.connect(natsUrl)) {
            jsm = conn.jetStreamManagement();
            conn.jetStream();

            try {
                var si = jsm.getStreamInfo("state_machine");
                if (si != null) {
                    jsm.deleteConsumer("state_machine", "cn-subscriber");
                    jsm.deleteConsumer("state_machine", "tp-subscriber");
                    deleteStream("state_machine");
                }
            } catch (Exception ignored) {

            }

            createStream("state_machine", "negotiations.>", "transfers.>");
            createConsumer("state_machine", "cn-subscriber", "negotiations.>");
            createConsumer("state_machine", "tp-subscriber", "transfers.>");
        } catch (Exception e) {
            throw new EdcException("Could not connect to NATS", e);
        }
    }

    public void createStream(String streamName, String... subject) {
        var streamConfig = StreamConfiguration.builder()
                .name(streamName)
                .subjects(subject)
                .storageType(StorageType.Memory)
                .build();
        try {
            jsm.addStream(streamConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void deleteStream(String streamName) {
        try {
            jsm.deleteStream(streamName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void createConsumer(String streamName, String consumerName, String filterSubject) {
        try {
            jsm.addOrUpdateConsumer(streamName, io.nats.client.api.ConsumerConfiguration.builder()
                    .durable(consumerName)
                    .name(consumerName)
                    .filterSubject(filterSubject)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
