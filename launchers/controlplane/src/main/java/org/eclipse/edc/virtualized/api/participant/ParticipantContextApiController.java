package org.eclipse.edc.virtualized.api.participant;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.participantcontext.spi.config.service.ParticipantContextConfigService;
import org.eclipse.edc.participantcontext.spi.service.ParticipantContextService;
import org.eclipse.edc.participantcontext.spi.types.ParticipantContext;
import org.eclipse.edc.participantcontext.spi.types.ParticipantContextState;
import org.eclipse.edc.spi.result.ServiceFailure;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.system.configuration.ConfigFactory;
import org.eclipse.edc.virtualized.api.participant.model.ParticipantManifest;

import java.net.URI;
import java.util.Base64;
import java.util.Map;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
@Path("/v1alpha/participants")
public class ParticipantContextApiController implements ParticipantContextApi {

    private final ParticipantContextService participantContextStore;
    private final ParticipantContextConfigService configService;
    private final Vault vault;
    private static final String TOKEN_URL = "edc.iam.sts.oauth.token.url";
    private static final String CLIENT_ID = "edc.iam.sts.oauth.client.id";
    private static final String CLIENT_SECRET_ALIAS = "edc.iam.sts.oauth.client.secret.alias";
    private static final String ISSUER_ID = "edc.iam.issuer.id";

    public ParticipantContextApiController(ParticipantContextService participantContextStore, ParticipantContextConfigService configService, Vault vault) {
        this.participantContextStore = participantContextStore;
        this.configService = configService;
        this.vault = vault;
    }

    @Path("/")
    @POST
    @Override
    public Response createParticipant(ParticipantManifest manifest) {
        var participantContextId = manifest.getParticipantContextId();

        var participantContext = ParticipantContext.Builder.newInstance()
                .participantContextId(participantContextId)
                .state( manifest.isActive() ? ParticipantContextState.ACTIVATED : ParticipantContextState.CREATED)
                .build();
        var result = participantContextStore.createParticipantContext(participantContext);

        if(result.failed()){
            return parseError(result.getFailure());
        }

        var config = Map.of(TOKEN_URL, manifest.getTokenUrl(),
                CLIENT_ID, manifest.getClientId(),
                CLIENT_SECRET_ALIAS, manifest.getClientSecretAlias(),
                ISSUER_ID, manifest.getParticipantId());

        var configResult = configService.save(participantContextId, ConfigFactory.fromMap(config));
        if(configResult.failed()){
            return parseError(configResult.getFailure());
        }

        if(vault.storeSecret(manifest.getClientSecretAlias(), manifest.getClientSecret()).failed()){
            return Response.status(500).build();
        }


        var base64 = Base64.getUrlEncoder().encodeToString(participantContext.getParticipantContextId().getBytes());
        return  Response.created(URI.create("/v1alpha/participants/"+base64)).build();
    }

    private Response parseError(ServiceFailure failure) {
        return switch(failure.getReason()){
            case NOT_FOUND -> Response.status(404).build();
            case CONFLICT -> Response.status(409).build();
            case BAD_REQUEST -> Response.status(400).build();
            case UNAUTHORIZED -> Response.status(401).build();
            case UNEXPECTED -> Response.status(500).build();
        };
    }
}
