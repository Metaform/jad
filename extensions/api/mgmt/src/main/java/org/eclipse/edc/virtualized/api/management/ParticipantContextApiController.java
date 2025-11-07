package org.eclipse.edc.virtualized.api.management;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.result.ServiceFailure;
import org.eclipse.edc.virtualized.service.OnboardingException;
import org.eclipse.edc.virtualized.service.OnboardingService;

import java.net.URI;
import java.util.Base64;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
@Path("/v1alpha/participants")
public class ParticipantContextApiController {

    private final OnboardingService onboardingService;


    public ParticipantContextApiController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @Path("/")
    @POST
    public Response createParticipant(ParticipantManifest manifest) {

        try {
            onboardingService.onboardParticipant(manifest);
            var base64 = Base64.getUrlEncoder().encodeToString(manifest.getParticipantContextId().getBytes());
            return Response.created(URI.create("/v1alpha/participants/" + base64)).build();
        } catch (EdcException e) {
            if (e.getCause() instanceof OnboardingException obe)
                return parseError(obe.getFailure());
            else
                return Response.serverError().entity(e.getMessage()).build();
        }
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


}
