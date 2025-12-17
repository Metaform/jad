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

package org.eclipse.edc.virtualized;

import org.eclipse.edc.connector.controlplane.services.spi.catalog.CatalogService;
import org.eclipse.edc.connector.controlplane.services.spi.contractnegotiation.ContractNegotiationService;
import org.eclipse.edc.connector.controlplane.services.spi.transferprocess.TransferProcessService;
import org.eclipse.edc.connector.dataplane.selector.spi.DataPlaneSelectorService;
import org.eclipse.edc.edr.spi.store.EndpointDataReferenceStore;
import org.eclipse.edc.iam.did.spi.resolution.DidResolverRegistry;
import org.eclipse.edc.participantcontext.spi.service.ParticipantContextService;
import org.eclipse.edc.runtime.metamodel.annotation.Configuration;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.runtime.metamodel.annotation.Settings;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.virtualized.api.data.DataApiController;
import org.eclipse.edc.virtualized.api.management.DataplaneRegistrationApiController;
import org.eclipse.edc.virtualized.service.DataRequestService;
import org.eclipse.edc.web.spi.WebService;
import org.eclipse.edc.web.spi.configuration.ApiContext;
import org.eclipse.edc.web.spi.configuration.context.ControlApiUrl;

import java.net.URI;


public class ApiExtension implements ServiceExtension {
    @Inject
    private WebService webService;
    @Configuration
    private ControlApiConfiguration controlApiConfiguration;
    @Inject
    private Vault vault;
    @Inject
    private CatalogService catalogService;
    @Inject
    private DidResolverRegistry didResolverRegistry;
    @Inject
    private ParticipantContextService participantContextService;
    @Inject
    private DataPlaneSelectorService selectorService;
    @Inject
    private ContractNegotiationService contractNegotiationService;
    @Inject
    private TransferProcessService transferProcessService;
    @Inject
    private EndpointDataReferenceStore edrStore;

    @Override
    public void initialize(ServiceExtensionContext context) {
        var dataRequestService = new DataRequestService(contractNegotiationService, transferProcessService, didResolverRegistry, edrStore);
        webService.registerResource(ApiContext.MANAGEMENT, new DataApiController(catalogService, didResolverRegistry, participantContextService, dataRequestService));
        webService.registerResource(ApiContext.MANAGEMENT, new DataplaneRegistrationApiController(selectorService));
    }

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


