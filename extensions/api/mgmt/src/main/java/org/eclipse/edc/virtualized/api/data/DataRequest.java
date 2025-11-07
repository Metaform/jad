package org.eclipse.edc.virtualized.api.data;

public record DataRequest(
        String providerId,
        String policyId
) {
}
