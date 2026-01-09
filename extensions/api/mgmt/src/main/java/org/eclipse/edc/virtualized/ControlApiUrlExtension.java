/*
 *  Copyright (c) 2026 Metaform Systems, Inc.
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

package org.eclipse.edc.virtualized;

import org.eclipse.edc.runtime.metamodel.annotation.Configuration;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.runtime.metamodel.annotation.Settings;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.web.spi.configuration.ApiContext;
import org.eclipse.edc.web.spi.configuration.context.ControlApiUrl;

import java.net.URI;

import static org.eclipse.edc.virtualized.ControlApiUrlExtension.NAME;

@Extension(value = NAME)
public class ControlApiUrlExtension implements ServiceExtension {
    public static final String NAME = "EDC-V Control API URL Extension";
    @Configuration
    private ControlApiConfiguration controlApiConfiguration;

    @Provider
    public ControlApiUrl controlApiUrl() {
        return () -> URI.create("http://controlplane.edc-v.svc.cluster.local:%s%s".formatted(controlApiConfiguration.port(), controlApiConfiguration.path()));
    }

    @Settings
    record ControlApiConfiguration(
            @Setting(key = "web.http." + ApiContext.CONTROL + ".port", description = "Port for " + ApiContext.CONTROL + " api context")
            int port,
            @Setting(key = "web.http." + ApiContext.CONTROL + ".path", description = "Path for " + ApiContext.CONTROL + " api context")
            String path
    ) {

    }
}
