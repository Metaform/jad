package org.eclipse.edc.virtualized.service;

import org.eclipse.edc.connector.controlplane.contract.spi.ContractOfferId;
import org.eclipse.edc.connector.controlplane.contract.spi.types.agreement.ContractAgreement;
import org.eclipse.edc.connector.controlplane.contract.spi.types.negotiation.ContractNegotiationStates;
import org.eclipse.edc.connector.controlplane.contract.spi.types.negotiation.ContractRequest;
import org.eclipse.edc.connector.controlplane.contract.spi.types.offer.ContractOffer;
import org.eclipse.edc.connector.controlplane.services.spi.contractnegotiation.ContractNegotiationService;
import org.eclipse.edc.connector.controlplane.services.spi.transferprocess.TransferProcessService;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.TransferProcess;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.TransferProcessStates;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.TransferRequest;
import org.eclipse.edc.edr.spi.store.EndpointDataReferenceStore;
import org.eclipse.edc.iam.did.spi.document.Service;
import org.eclipse.edc.iam.did.spi.resolution.DidResolverRegistry;
import org.eclipse.edc.participantcontext.spi.types.ParticipantContext;
import org.eclipse.edc.policy.model.PolicyType;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.result.ServiceResult;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.virtualized.api.data.DataRequest;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

import static java.net.http.HttpClient.newHttpClient;
import static java.util.Optional.ofNullable;

/**
 * this is a wrapper service that initiates the contract negotiation and the transfer process, waits for its completion, and then downloads the data.
 * I implemented this because there is no multi-tenant management API yet that.
 */
public class DataRequestService {

    private final ContractNegotiationService contractNegotiationService;
    private final TransferProcessService transferProcessService;
    private final DidResolverRegistry didResolverRegistry;
    private final EndpointDataReferenceStore edrStore;

    public DataRequestService(ContractNegotiationService contractNegotiationService, TransferProcessService transferProcessService, DidResolverRegistry didResolverRegistry, EndpointDataReferenceStore edrStore) {
        this.contractNegotiationService = contractNegotiationService;
        this.transferProcessService = transferProcessService;
        this.didResolverRegistry = didResolverRegistry;
        this.edrStore = edrStore;
    }

    public CompletableFuture<ServiceResult<Object>> getData(ParticipantContext participantContext, DataRequest dataRequest) {
        return initiateContractNegotiation(participantContext, dataRequest)
                .thenCompose(this::waitForContractNegotiation)
                .thenCompose(contractNegotiation -> startTransferProcess(participantContext, contractNegotiation))
                .thenCompose(this::waitForTransferProcess)
                .thenCompose(transferProcess -> getEdr(transferProcess.getId()))
                .thenCompose(this::downloadData)
                .thenApply(ServiceResult::success);
    }

    private CompletableFuture<String> initiateContractNegotiation(ParticipantContext participantContext, DataRequest dataRequest) {
        var addressForDid = getAddressForDid(dataRequest.providerId());
        if (addressForDid.failed()) {
            return CompletableFuture.failedFuture(new RuntimeException("Could not resolve address for did: %s".formatted(addressForDid.getFailureDetail())));
        }
        var offerId = ContractOfferId.parseId(dataRequest.policyId());
        var rq = ContractRequest.Builder.newInstance()
                .protocol("dataspace-protocol-http:2025-1")
                .counterPartyAddress(addressForDid.getContent())
                .contractOffer(ContractOffer.Builder.newInstance()
                        .id(dataRequest.policyId())
                        .assetId(offerId.getContent().assetIdPart())
                        .policy(Data.MEMBERSHIP_POLICY.toBuilder()
                                .target(offerId.getContent().assetIdPart())
                                .assigner(dataRequest.providerId())
                                .type(PolicyType.OFFER)
                                .build())
                        .build())
                .build();
        var negotiation = contractNegotiationService.initiateNegotiation(participantContext, rq);

        return CompletableFuture.completedFuture(negotiation.getId());
    }

    private Result<String> getAddressForDid(String counterPartyDid) {
        var did = didResolverRegistry.resolve(counterPartyDid);
        if (did.failed()) {
            return did.mapFailure();
        }

        var doc = did.getContent();

        var protocolEndpoint = doc.getService().stream().filter(s -> s.getType().equals("ProtocolEndpoint")).findFirst();
        return Result.from(protocolEndpoint).map(Service::getServiceEndpoint);
    }

    private CompletableFuture<ContractAgreement> waitForContractNegotiation(String contractNegotiationId) {

        try {
            ContractNegotiationStates state;
            do {
                state = ContractNegotiationStates.valueOf(contractNegotiationService.getState(contractNegotiationId));
                Thread.sleep(1000);
            } while (state != ContractNegotiationStates.FINALIZED && state != ContractNegotiationStates.TERMINATED);

            if (state == ContractNegotiationStates.TERMINATED) {
                return CompletableFuture.failedFuture(new EdcException("Contract negotiation terminated"));
            }

            return CompletableFuture.completedFuture(contractNegotiationService.getForNegotiation(contractNegotiationId));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<TransferProcess> startTransferProcess(ParticipantContext participantContext, ContractAgreement agreement) {

        var request = TransferRequest.Builder.newInstance()
                .contractId(agreement.getId())
                .counterPartyAddress(getAddressForDid(agreement.getProviderId()).getContent())
                .protocol("dataspace-protocol-http:2025-1")
                .transferType("HttpData-PULL")
                .dataDestination(DataAddress.Builder.newInstance().type("httpData").build())
                .build();

        var result = transferProcessService.initiateTransfer(participantContext, request);
        if (result.succeeded())
            return CompletableFuture.completedFuture(result.getContent());
        else {
            return CompletableFuture.failedFuture(new EdcException("Could not start transfer process: %s".formatted(result.getFailureDetail())));
        }
    }

    private CompletableFuture<TransferProcess> waitForTransferProcess(TransferProcess transferProcess) {
        try {
            TransferProcessStates state;
            do {
                state = TransferProcessStates.valueOf(transferProcessService.getState(transferProcess.getId()));
                Thread.sleep(1000);
            } while (state != TransferProcessStates.STARTED && state != TransferProcessStates.TERMINATED);

            var tp = transferProcessService.findById(transferProcess.getId());
            if (state == TransferProcessStates.TERMINATED) {
                return CompletableFuture.failedFuture(new EdcException("Transfer process terminated: %s".formatted(ofNullable(tp).map(TransferProcess::getErrorDetail).orElse("provider terminated"))));
            }
            return CompletableFuture.completedFuture(tp);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<DataAddress> getEdr(String transferProcessId) {
        var edr = edrStore.resolveByTransferProcess(transferProcessId);
        if (edr.failed()) {
            return CompletableFuture.failedFuture(new EdcException("Could not resolve EDR for transfer process: %s".formatted(edr.getFailureDetail())));
        }
        return CompletableFuture.completedFuture(edr.getContent());
    }

    private CompletableFuture<String> downloadData(DataAddress edr) {

        // make HTTP request
        if (edr.getType().equals("https://w3id.org/idsa/v4.1/HTTP")) {

            var endpoint = edr.getStringProperty("https://w3id.org/edc/v0.0.1/ns/endpoint");
            var token = edr.getStringProperty("https://w3id.org/edc/v0.0.1/ns/authorization");
            var authType = edr.getStringProperty("https://w3id.org/edc/v0.0.1/ns/authType");

            if (endpoint == null) {
                return CompletableFuture.failedFuture(new EdcException("Endpoint not found in EDR"));
            }

            var request = HttpRequest.newBuilder(URI.create(endpoint))
                    .GET()
                    .header("Authorization", token)
                    .build();
            return newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenCompose(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            return CompletableFuture.completedFuture(response.body());
                        }
                        return CompletableFuture.failedFuture(new EdcException("Dataplane request failed: HTTP Status code: %s, message: %s".formatted(response.statusCode(), response.body())));
                    });
        }
        return CompletableFuture.failedFuture(new EdcException("EDR type not supported: %s".formatted(edr.getType())));
    }
}
