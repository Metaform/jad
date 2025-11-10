package org.eclipse.edc.virtualized;

import org.eclipse.edc.connector.controlplane.services.spi.asset.AssetService;
import org.eclipse.edc.connector.controlplane.services.spi.catalog.CatalogService;
import org.eclipse.edc.connector.controlplane.services.spi.contractdefinition.ContractDefinitionService;
import org.eclipse.edc.connector.controlplane.services.spi.contractnegotiation.ContractNegotiationService;
import org.eclipse.edc.connector.controlplane.services.spi.policydefinition.PolicyDefinitionService;
import org.eclipse.edc.connector.controlplane.services.spi.transferprocess.TransferProcessService;
import org.eclipse.edc.connector.dataplane.selector.spi.DataPlaneSelectorService;
import org.eclipse.edc.edr.spi.store.EndpointDataReferenceStore;
import org.eclipse.edc.iam.did.spi.resolution.DidResolverRegistry;
import org.eclipse.edc.participantcontext.spi.config.service.ParticipantContextConfigService;
import org.eclipse.edc.participantcontext.spi.service.ParticipantContextService;
import org.eclipse.edc.runtime.metamodel.annotation.Configuration;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.runtime.metamodel.annotation.Settings;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.transaction.spi.TransactionContext;
import org.eclipse.edc.virtualized.api.data.DataApiController;
import org.eclipse.edc.virtualized.api.management.ParticipantContextApiController;
import org.eclipse.edc.virtualized.service.DataRequestService;
import org.eclipse.edc.virtualized.service.OnboardingService;
import org.eclipse.edc.web.spi.WebService;
import org.eclipse.edc.web.spi.configuration.ApiContext;
import org.eclipse.edc.web.spi.configuration.context.ControlApiUrl;

import java.net.URI;


public class ApiExtension implements ServiceExtension {
    @Inject
    private WebService webService;
    @Inject
    private ParticipantContextService service;
    @Configuration
    private ControlApiConfiguration controlApiConfiguration;
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
    @Inject
    private TransactionContext transactionContext;
    @Inject
    private ContractNegotiationService contractNegotiationService;
    @Inject
    private TransferProcessService transferProcessService;
    @Inject
    private EndpointDataReferenceStore edrStore;

    @Override
    public void initialize(ServiceExtensionContext context) {
        var onboardingService = new OnboardingService(transactionContext, service, configService, vault, selectorService, assetService, policyService, contractDefinitionService);
        webService.registerResource(ApiContext.MANAGEMENT, new ParticipantContextApiController(onboardingService));
        var dataRequestService = new DataRequestService(contractNegotiationService, transferProcessService, didResolverRegistry, edrStore);
        webService.registerResource(ApiContext.MANAGEMENT, new DataApiController(catalogService, didResolverRegistry, participantContextService, dataRequestService));

    }

    @Provider
    public ControlApiUrl controlApiUrl() {
        return () -> URI.create("http://controlplane.edc-v.svc.cluster.local:%s%s".formatted(controlApiConfiguration.port(), controlApiConfiguration.path()));
    }
}


@Settings
record ControlApiConfiguration(
        @Setting(key = "web.http." + ApiContext.CONTROL + ".port", description = "Port for " + ApiContext.CONTROL + " api context")
        int port,
        @Setting(key = "web.http." + ApiContext.CONTROL + ".path", description = "Path for " + ApiContext.CONTROL + " api context")
        String path
) {

}
