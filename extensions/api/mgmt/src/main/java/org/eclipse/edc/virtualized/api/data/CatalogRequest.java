package org.eclipse.edc.virtualized.api.data;

import org.eclipse.edc.spi.query.QuerySpec;

public final class CatalogRequest {
    private String counterPartyDid;
    private String protocol = "dataspace-protocol-http:2025-1";
    private QuerySpec query = QuerySpec.max();

    public String getCounterPartyDid() {
        return counterPartyDid;
    }

    public void setCounterPartyDid(String counterPartyDid) {
        this.counterPartyDid = counterPartyDid;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public QuerySpec getQuery() {
        return query;
    }

    public void setQuery(QuerySpec query) {
        this.query = query;
    }
}
