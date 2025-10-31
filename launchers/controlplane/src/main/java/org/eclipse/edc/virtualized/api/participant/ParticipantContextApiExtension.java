package org.eclipse.edc.virtualized.api.participant;

import org.eclipse.edc.participantcontext.spi.config.service.ParticipantContextConfigService;
import org.eclipse.edc.participantcontext.spi.service.ParticipantContextService;
import org.eclipse.edc.participantcontext.spi.store.ParticipantContextStore;
import org.eclipse.edc.runtime.metamodel.annotation.Configuration;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.runtime.metamodel.annotation.Settings;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.web.spi.WebService;
import org.eclipse.edc.web.spi.configuration.ApiContext;
import org.eclipse.edc.web.spi.configuration.PortMapping;
import org.eclipse.edc.web.spi.configuration.PortMappingRegistry;


public class ParticipantContextApiExtension implements ServiceExtension {
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

    @Override
    public void initialize(ServiceExtensionContext context) {
        var portMapping = new PortMapping(ApiContext.MANAGEMENT, apiConfiguration.port(), apiConfiguration.path());
        portMappingRegistry.register(portMapping);


        webService.registerResource(ApiContext.MANAGEMENT, new ParticipantContextApiController(service, configService, vault));

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
