package org.eclipse.edc.virtualized.api.management;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.connector.controlplane.services.spi.catalog.CatalogService;
import org.eclipse.edc.iam.did.spi.resolution.DidResolverRegistry;
import org.eclipse.edc.participantcontext.spi.service.ParticipantContextService;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.response.StatusResult;
import org.eclipse.edc.web.spi.exception.BadGatewayException;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
@Path("/v1alpha/participants/{participantContextId}/")
public class WrapperApiController {

    private final CatalogService service;
    private final DidResolverRegistry didResolverRegistry;
    private final ParticipantContextService participantContextService;

    public WrapperApiController(CatalogService service, DidResolverRegistry didResolverRegistry, ParticipantContextService participantContextService) {
        this.service = service;
        this.didResolverRegistry = didResolverRegistry;
        this.participantContextService = participantContextService;
    }


    @POST
    @Path("/catalog")
    public void getCatalog(@PathParam("participantContextId") String participantContextId,
                           CatalogRequest catalogRequest,
                           @Suspended AsyncResponse response) {

        var participantContext = participantContextService.getParticipantContext(participantContextId);
        if (participantContext.failed()) {
            response.resume(Response.status(404).entity("Participant context '%s' not found".formatted(participantContextId)).build());
        }

        var counterPartyDid = catalogRequest.getCounterPartyDid();
        var did = didResolverRegistry.resolve(counterPartyDid);
        if (did.failed()) {
            response.resume(Response.status(400).entity("Could not resolve DID '%s': %s".formatted(counterPartyDid, did.getFailureDetail())).build());
        }

        var doc = did.getContent();

        var protocolEndpoint = doc.getService().stream().filter(s -> s.getType().equals("ProtocolEndpoint")).findFirst();
        if (protocolEndpoint.isEmpty()) {
            response.resume(Response.status(400).entity("No ProtocolEndpoint service found in DID Document for '%s'".formatted(counterPartyDid)).build());
        }
        var counterPartyAddress = protocolEndpoint.get().getServiceEndpoint();


        var catalog = service.requestCatalog(participantContext.getContent(), counterPartyDid, counterPartyAddress, catalogRequest.getProtocol(), catalogRequest.getQuery());

        catalog.whenComplete((result, throwable) -> {
            try {
                response.resume(toResponse(result, throwable));
            } catch (Throwable mapped) {
                response.resume(mapped);
            }
        });

    }

    private byte[] toResponse(StatusResult<byte[]> result, Throwable throwable) throws Throwable {
        if (throwable == null) {
            if (result.succeeded()) {
                return result.getContent();
            } else {
                throw new BadGatewayException(result.getFailureDetail());
            }
        } else {
            if (throwable instanceof EdcException || throwable.getCause() instanceof EdcException) {
                throw new BadGatewayException(throwable.getMessage());
            } else {
                throw throwable;
            }
        }
    }

}
