package org.eclipse.edc.virtualized.service;

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
import org.eclipse.edc.spi.query.Criterion;
import org.eclipse.edc.spi.result.ServiceFailure;
import org.eclipse.edc.spi.result.ServiceResult;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.system.configuration.ConfigFactory;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.transaction.spi.TransactionContext;
import org.eclipse.edc.virtualized.api.management.ParticipantManifest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * This service is a quick-n-dirty onboarding agent, that performs all necessary tasks required to onboard a new participant into the control plane:
 * <ul>
 *     <li>creates a participant context in the database</li>
 *     <li>creates participant configuration</li>
 *     <li>stores all the participant's secrets in the vault</li>
 *     <li>"creates" a data plane for this participant</li>
 *     <li>creates an asset, a policy, and a contract definition</li>
 * </ul>
 */
public class OnboardingService {
    private static final String TOKEN_URL = "edc.iam.sts.oauth.token.url";
    private static final String CLIENT_ID = "edc.iam.sts.oauth.client.id";
    private static final String CLIENT_SECRET_ALIAS = "edc.iam.sts.oauth.client.secret.alias";
    private static final String ISSUER_ID = "edc.iam.issuer.id";
    private static final String PARTICIPANT_ID = "edc.participant.id";
    private final TransactionContext transactionContext;
    private final ParticipantContextService participantContextStore;
    private final ParticipantContextConfigService configService;
    private final Vault vault;
    private final DataPlaneSelectorService dataPlaneSelectorService;
    private final AssetService assetService;
    private final PolicyDefinitionService policyService;
    private final ContractDefinitionService contractDefinitionService;

    public OnboardingService(TransactionContext transactionContext, ParticipantContextService participantContextStore, ParticipantContextConfigService configService, Vault vault, DataPlaneSelectorService dataPlaneSelectorService, AssetService assetService, PolicyDefinitionService policyService, ContractDefinitionService contractDefinitionService) {
        this.transactionContext = transactionContext;
        this.participantContextStore = participantContextStore;
        this.configService = configService;
        this.vault = vault;
        this.dataPlaneSelectorService = dataPlaneSelectorService;
        this.assetService = assetService;
        this.policyService = policyService;
        this.contractDefinitionService = contractDefinitionService;
    }

    public void onboardParticipant(ParticipantManifest manifest) {

        var participantContextId = manifest.getParticipantContextId();
        var participantContext = ParticipantContext.Builder.newInstance()
                .participantContextId(participantContextId)
                .state(manifest.isActive() ? ParticipantContextState.ACTIVATED : ParticipantContextState.CREATED)
                .build();

        var participantConfig = Map.of(TOKEN_URL, manifest.getTokenUrl(),
                CLIENT_ID, manifest.getClientId(),
                CLIENT_SECRET_ALIAS, manifest.getClientSecretAlias(),
                ISSUER_ID, manifest.getParticipantId(),
                PARTICIPANT_ID, manifest.getParticipantId());

        transactionContext.execute(() -> {

            participantContextStore.createParticipantContext(participantContext)
                    .orElseThrow(OnboardingException::new);

            configService.save(participantContextId, ConfigFactory.fromMap(participantConfig))
                    .orElseThrow(OnboardingException::new);

            vault.storeSecret(manifest.getClientSecretAlias(), manifest.getClientSecret())
                    .orElseThrow(f -> new OnboardingException(new ServiceFailure(f.getMessages(), ServiceFailure.Reason.UNEXPECTED)));

            dataPlaneSelectorService.addInstance(DataPlaneInstance.Builder.newInstance()
                            .participantContextId(manifest.getParticipantContextId())
                            .allowedSourceType("HttpData")
                            .allowedTransferType("HttpData-PULL")
                            .url("http://dataplane.edc-v.svc.cluster.local:8083/api/control/v1/dataflows")
                            .build())
                    .orElseThrow(OnboardingException::new);


            var assetId = UUID.randomUUID().toString();
            createAssets(assetId, participantContextId)
                    .compose(a -> createPolicies(participantContextId))
                    .compose(p -> createContractDefinitions(assetId, p.getId(), participantContextId))
                    .orElseThrow(OnboardingException::new);
        });

    }

    private ServiceResult<PolicyDefinition> createPolicies(String participantContextId) {
        var policy = PolicyDefinition.Builder.newInstance()
                .id(UUID.randomUUID().toString())
                .participantContextId(participantContextId)
                .policy(Data.MEMBERSHIP_POLICY)
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
                        .property("https://w3id.org/edc/v0.0.1/ns/baseUrl", "https://jsonplaceholder.typicode.com/todos")
                        .property("https://w3id.org/edc/v0.0.1/ns/proxyPath", "true")
                        .property("https://w3id.org/edc/v0.0.1/ns/proxyQueryParams", "true")
                        .build())
                .build();
        return assetService.create(asset1);
    }
}
