package org.eclipse.edc.virtualized.api.participant;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.connector.controlplane.asset.spi.domain.Asset;
import org.eclipse.edc.connector.controlplane.contract.spi.types.offer.ContractDefinition;
import org.eclipse.edc.connector.controlplane.policy.spi.PolicyDefinition;
import org.eclipse.edc.connector.controlplane.services.spi.asset.AssetService;
import org.eclipse.edc.connector.controlplane.services.spi.contractdefinition.ContractDefinitionService;
import org.eclipse.edc.connector.controlplane.services.spi.policydefinition.PolicyDefinitionService;
import org.eclipse.edc.connector.dataplane.selector.spi.DataPlaneSelectorService;
import org.eclipse.edc.connector.dataplane.selector.spi.instance.DataPlaneInstance;
import org.eclipse.edc.participantcontext.spi.config.service.ParticipantContextConfigService;
import org.eclipse.edc.participantcontext.spi.service.ParticipantContextService;
import org.eclipse.edc.participantcontext.spi.types.ParticipantContext;
import org.eclipse.edc.participantcontext.spi.types.ParticipantContextState;
import org.eclipse.edc.policy.model.Action;
import org.eclipse.edc.policy.model.AtomicConstraint;
import org.eclipse.edc.policy.model.LiteralExpression;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.spi.query.Criterion;
import org.eclipse.edc.spi.result.ServiceFailure;
import org.eclipse.edc.spi.result.ServiceResult;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.system.configuration.ConfigFactory;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.virtualized.api.participant.model.ParticipantManifest;

import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
@Path("/v1alpha/participants")
public class ParticipantContextApiController {

    private static final String TOKEN_URL = "edc.iam.sts.oauth.token.url";
    private static final String CLIENT_ID = "edc.iam.sts.oauth.client.id";
    private static final String CLIENT_SECRET_ALIAS = "edc.iam.sts.oauth.client.secret.alias";
    private static final String ISSUER_ID = "edc.iam.issuer.id";
    private static final String PARTICIPANT_ID = "edc.participant.id";
    private final ParticipantContextService participantContextStore;
    private final ParticipantContextConfigService configService;
    private final Vault vault;
    private final DataPlaneSelectorService dataPlaneSelectorService;
    private final AssetService assetService;
    private final PolicyDefinitionService policyService;
    private final ContractDefinitionService contractDefinitionService;

    public ParticipantContextApiController(ParticipantContextService participantContextStore, ParticipantContextConfigService configService, Vault vault, DataPlaneSelectorService dataPlaneSelectorService, AssetService assetService, PolicyDefinitionService policyService, ContractDefinitionService contractDefinitionService) {
        this.participantContextStore = participantContextStore;
        this.configService = configService;
        this.vault = vault;
        this.dataPlaneSelectorService = dataPlaneSelectorService;
        this.assetService = assetService;
        this.policyService = policyService;
        this.contractDefinitionService = contractDefinitionService;
    }

    @Path("/")
    @POST
    public Response createParticipant(ParticipantManifest manifest) {
        var participantContextId = manifest.getParticipantContextId();

        var participantContext = ParticipantContext.Builder.newInstance()
                .participantContextId(participantContextId)
                .state(manifest.isActive() ? ParticipantContextState.ACTIVATED : ParticipantContextState.CREATED)
                .build();
        var result = participantContextStore.createParticipantContext(participantContext);

        if (result.failed()) {
            return parseError(result.getFailure());
        }

        var config = Map.of(TOKEN_URL, manifest.getTokenUrl(),
                CLIENT_ID, manifest.getClientId(),
                CLIENT_SECRET_ALIAS, manifest.getClientSecretAlias(),
                ISSUER_ID, manifest.getParticipantId(),
                PARTICIPANT_ID, manifest.getParticipantId());

        var configResult = configService.save(participantContextId, ConfigFactory.fromMap(config));
        if (configResult.failed()) {
            return parseError(configResult.getFailure());
        }

        if (vault.storeSecret(manifest.getClientSecretAlias(), manifest.getClientSecret()).failed()) {
            return Response.status(500).build();
        }

        var res = dataPlaneSelectorService.addInstance(DataPlaneInstance.Builder.newInstance()
                .participantContextId(manifest.getParticipantContextId())
                .allowedSourceType("HttpData")
                .allowedTransferType("HttpData-PULL")
                .url("http://dataplane.edc-v.cluster.svc.local:8083/api/control/v1/dataflows")
                .build());

        if(res.failed()){
            return Response.status(500).entity(res.getFailureDetail()).build();
        }


        var assetId = UUID.randomUUID().toString();
        var seedRes = createAssets(assetId, participantContextId)
                .compose(a -> createPolicies( participantContextId))
                .compose(p -> createContractDefinitions(assetId, p.getId(), participantContextId));

        if(seedRes.failed()){
            return Response.status(500).entity(seedRes.getFailureDetail()).build();
        }

        var base64 = Base64.getUrlEncoder().encodeToString(participantContext.getParticipantContextId().getBytes());
        return Response.created(URI.create("/v1alpha/participants/" + base64)).build();
    }

    private Response parseError(ServiceFailure failure) {
        return switch (failure.getReason()) {
            case NOT_FOUND -> Response.status(404).build();
            case CONFLICT -> Response.status(409).build();
            case BAD_REQUEST -> Response.status(400).build();
            case UNAUTHORIZED -> Response.status(401).build();
            case UNEXPECTED -> Response.status(500).build();
        };
    }

    private ServiceResult<PolicyDefinition> createPolicies(String participantContextId) {
        var policy = PolicyDefinition.Builder.newInstance()
                .id(UUID.randomUUID().toString())
                .participantContextId(participantContextId)
                .policy(Policy.Builder.newInstance()
                        .permission(Permission.Builder.newInstance()
                                .action(Action.Builder.newInstance()
                                        .type("use")
                                        .build())
                                .constraint(AtomicConstraint.Builder.newInstance()
                                        .leftExpression(new LiteralExpression("MembershipCredential"))
                                        .operator(Operator.EQ)
                                        .rightExpression(new LiteralExpression("active"))
                                        .build())
                                .build())
                        .build())
                .build();

        return policyService.create(policy);
    }

    private ServiceResult<ContractDefinition> createContractDefinitions(String assetId, String policyId, String participantContextId) {

        var contractDefinition = ContractDefinition.Builder.newInstance()
                .id(UUID.randomUUID().toString())
                .participantContextId(participantContextId)
                .contractPolicyId(policyId)
                .accessPolicyId(policyId)
                .assetsSelector(List.of(new Criterion("https://w3id.org/edc/v0.0.1/ns/id", "=", assetId)))
                .build();
        return contractDefinitionService.create(contractDefinition);
    }

    private ServiceResult<Asset> createAssets(String assetId, String participantContextId) {
        var asset1 = Asset.Builder.newInstance()
                .id(assetId)
                .participantContextId(participantContextId)
                .property("description", "This asset requires the Membership credential to access")
                .dataAddress(DataAddress.Builder.newInstance()
                        .type("HttpData")
                        .property("baseUrl", "https://jsonplaceholder.typicode.com/todos")
                        .property("proxyPath", "true")
                        .property("proxyQueryParams", "true")
                        .build())
                .build();
        return assetService.create(asset1);
    }
}
