package org.eclipse.edc.virtualized.api;

import org.eclipse.edc.connector.controlplane.services.spi.asset.AssetService;
import org.eclipse.edc.connector.controlplane.services.spi.catalog.CatalogService;
import org.eclipse.edc.connector.controlplane.services.spi.contractdefinition.ContractDefinitionService;
import org.eclipse.edc.connector.controlplane.services.spi.policydefinition.PolicyDefinitionService;
import org.eclipse.edc.connector.dataplane.selector.spi.DataPlaneSelectorService;
import org.eclipse.edc.iam.did.spi.resolution.DidResolverRegistry;
import org.eclipse.edc.participantcontext.spi.config.service.ParticipantContextConfigService;
import org.eclipse.edc.participantcontext.spi.service.ParticipantContextService;
import org.eclipse.edc.runtime.metamodel.annotation.Configuration;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.runtime.metamodel.annotation.Settings;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.virtualized.api.management.WrapperApiController;
import org.eclipse.edc.virtualized.api.participant.ParticipantContextApiController;
import org.eclipse.edc.web.spi.WebService;
import org.eclipse.edc.web.spi.configuration.ApiContext;
import org.eclipse.edc.web.spi.configuration.PortMapping;
import org.eclipse.edc.web.spi.configuration.PortMappingRegistry;


public class ApiExtension implements ServiceExtension {
    @Inject
    private WebService webService;
    @Inject
    private ParticipantContextService service;

    @Configuration
    private ManagementApiConfiguration apiConfiguration;
    @Inject
    private PortMappingRegistry portMappingRegistry;
    @Inject
    private ParticipantContextConfigService configService;
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
    private AssetService assetService;
    @Inject
    private PolicyDefinitionService policyService;
    @Inject
    private ContractDefinitionService contractDefinitionService;

    @Override
    public void initialize(ServiceExtensionContext context) {
        var portMapping = new PortMapping(ApiContext.MANAGEMENT, apiConfiguration.port(), apiConfiguration.path());
        portMappingRegistry.register(portMapping);


        webService.registerResource(ApiContext.MANAGEMENT, new ParticipantContextApiController(service, configService, vault, selectorService, assetService, policyService, contractDefinitionService));
        webService.registerResource(ApiContext.MANAGEMENT, new WrapperApiController(catalogService, didResolverRegistry, participantContextService));

    }

    @Settings
    record ManagementApiConfiguration(
            @Setting(key = "web.http." + ApiContext.MANAGEMENT + ".port", description = "Port for " + ApiContext.MANAGEMENT + " api context", defaultValue =  "8081")
            int port,
            @Setting(key = "web.http." + ApiContext.MANAGEMENT + ".path", description = "Path for " + ApiContext.MANAGEMENT + " api context", defaultValue = "/api/mgmt")
            String path
    ) {

    }
}
