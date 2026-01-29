/*
 *  Copyright (c) 2025 Metaform Systems, Inc.
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Metaform Systems, Inc. - initial API and implementation
 *
 */

package org.eclipse.edc.virtualized.dataplane.cert.store.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.sql.QueryExecutor;
import org.eclipse.edc.sql.store.AbstractSqlStore;
import org.eclipse.edc.sql.translation.SqlQueryStatement;
import org.eclipse.edc.transaction.datasource.spi.DataSourceRegistry;
import org.eclipse.edc.transaction.spi.TransactionContext;
import org.eclipse.edc.virtualized.dataplane.cert.model.CertMetadata;
import org.eclipse.edc.virtualized.dataplane.cert.store.CertStore;

import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public class SqlCertStore extends AbstractSqlStore implements CertStore {

    public SqlCertStore(DataSourceRegistry dataSourceRegistry, String dataSourceName, TransactionContext transactionContext, ObjectMapper objectMapper, QueryExecutor queryExecutor) {
        super(dataSourceRegistry, dataSourceName, transactionContext, objectMapper, queryExecutor);
    }

    @Override
    public void store(String id, CertMetadata metadata, byte[] content) {
        transactionContext.execute(() -> {
            try (var connection = getConnection()) {
                var stmt = "INSERT INTO edc_certs (id, metadata, data) VALUES (?, ?::jsonb, ?)";
                var ps = connection.prepareStatement(stmt);
                ps.setString(1, id);
                ps.setString(2, toJson(metadata));
                ps.setBytes(3, content);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new EdcException(e);
            }
        });

    }

    @Override
    public void delete(String id) {
        transactionContext.execute(() -> {
            try (var connection = getConnection()) {
                var stmt = "DELETE FROM edc_certs WHERE id = ?";
                var ps = connection.prepareStatement(stmt);
                ps.setString(1, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new EdcException(e);
            }
        });
    }

    @Override
    public List<CertMetadata> queryMetadata(QuerySpec querySpec) {
        return transactionContext.execute(() -> {
            try (var connection = getConnection()) {
                var stmt = new SqlQueryStatement("SELECT metadata FROM edc_certs", querySpec.getLimit(), querySpec.getOffset());
                return queryExecutor.query(connection, true, this::mapMetadata, stmt.getQueryAsString(), stmt.getParameters()).toList();
            } catch (SQLException e) {
                throw new EdcException(e);
            }
        });
    }

    private CertMetadata mapMetadata(ResultSet resultSet) throws SQLException {
        var metadataJson = resultSet.getString("metadata");
        return fromJson(metadataJson, CertMetadata.class);
    }

    @Override
    public CertMetadata getMetadata(String id) {
        return transactionContext.execute(() -> {
            try (var connection = getConnection()) {
                var stmt = "SELECT metadata FROM edc_certs WHERE id = ?";
                return queryExecutor.query(connection, true, this::mapMetadata, stmt, id)
                        .findFirst()
                        .orElse(null);
            } catch (SQLException e) {
                throw new EdcException(e);
            }
        });
    }

    @Override
    public InputStream retrieve(String id) {
        return transactionContext.execute(() -> {
            try (var connection = getConnection()) {
                var stmt = "SELECT data FROM edc_certs WHERE id = ?";
                return queryExecutor.query(connection, true, rs -> rs.getBinaryStream("data"), stmt, id)
                        .findFirst()
                        .orElse(null);
            } catch (SQLException e) {
                throw new EdcException(e);
            }
        });
    }

    @Override
    public void updateMetadata(String id, CertMetadata metadata) {
        transactionContext.execute(() -> {
            try (var connection = getConnection()) {
                var stmt = "UPDATE edc_certs SET metadata = ?::jsonb WHERE id = ?";
                queryExecutor.execute(connection, stmt, toJson(metadata), id);
            } catch (SQLException e) {
                throw new EdcException(e);
            }
        });
    }
}
