// Copyright (c) 2026 Metaform Systems, Inc.
//
// This program and the accompanying materials are made available under the
// terms of the Apache License, Version 2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: Apache-2.0
//
// Contributors:
//      Metaform Systems, Inc. - initial API and implementation

// auth-proxy is a lightweight Traefik ForwardAuth target that validates
// Bearer tokens via Keycloak's token introspection endpoint (RFC 7662)
// and enforces per-route scope requirements.
//
// Traefik calls: GET /validate?scope=<s1>&scope=<s2>
//   - 200 → token is active and has at least one of the listed scopes
//   - 401 → missing/inactive token
//   - 403 → token is valid but lacks the required scopes

use async_trait::async_trait;
use http::{Response, StatusCode};
use pingora_core::apps::http_app::{HttpServer, ServeHttp};
use pingora_core::protocols::http::ServerSession;
use pingora_core::server::Server;
use pingora_core::services::listening::Service;
use reqwest::Client;
use serde::Deserialize;
use std::collections::HashSet;
use std::env;
use std::time::Duration;
use tracing::{debug, error, info, warn};
use tracing_subscriber::EnvFilter;

struct AuthProxy {
    introspect_url: String,
    client_id: String,
    client_secret: String,
    http_client: Client,
}

#[derive(Deserialize)]
struct IntrospectResponse {
    active: bool,
    #[serde(default)]
    scope: String,
}

impl AuthProxy {
    fn from_env() -> Self {
        AuthProxy {
            introspect_url: must_env("TOKEN_INTROSPECTION_URL"),
            client_id: must_env("INTROSPECT_CLIENT_ID"),
            client_secret: must_env("INTROSPECT_CLIENT_SECRET"),
            http_client: Client::builder()
                .timeout(Duration::from_secs(5))
                .build()
                .expect("failed to build HTTP client"),
        }
    }

    async fn introspect(&self, token: &str) -> Result<IntrospectResponse, reqwest::Error> {
        debug!(url = %self.introspect_url, "calling token introspection endpoint");
        let resp = self
            .http_client
            .post(&self.introspect_url)
            .form(&[
                ("token", token),
                ("client_id", self.client_id.as_str()),
                ("client_secret", self.client_secret.as_str()),
            ])
            .send()
            .await?
            .json::<IntrospectResponse>()
            .await?;
        debug!(active = resp.active, scopes = %resp.scope, "introspection response received");
        Ok(resp)
    }

    async fn handle_validate(&self, auth_header: Option<&str>, query: &str) -> Response<Vec<u8>> {
        let token = match auth_header.and_then(|h| h.strip_prefix("Bearer ")) {
            Some(t) if !t.is_empty() && t.len() <= 4096 => t,
            Some(_) => {
                warn!("request rejected: invalid token length");
                return text_response(StatusCode::UNAUTHORIZED, "invalid bearer token");
            }
            None => {
                warn!("request rejected: no bearer token");
                return text_response(StatusCode::UNAUTHORIZED, "missing bearer token");
            }
        };

        let result = match self.introspect(token).await {
            Ok(r) => r,
            Err(e) => {
                error!(error = %e, "introspection request failed");
                return text_response(StatusCode::INTERNAL_SERVER_ERROR, "internal error");
            }
        };

        if !result.active {
            warn!("request rejected: token inactive");
            return text_response(StatusCode::UNAUTHORIZED, "token inactive");
        }

        // ?scope= params are candidates; the token must carry at least one.
        let required: Vec<&str> = query
            .split('&')
            .filter_map(|kv| {
                let (k, v) = kv.split_once('=')?;
                (k == "scope").then_some(v)
            })
            .collect();

        if !required.is_empty() {
            let present: HashSet<&str> = result.scope.split_whitespace().collect();
            if !required.iter().any(|s| present.contains(s)) {
                warn!(required = ?required, present = ?present, "request rejected: insufficient scope");
                return text_response(StatusCode::FORBIDDEN, "insufficient scope");
            }
            debug!(required = ?required, "scope check passed");
        }

        debug!("request allowed");
        text_response(StatusCode::OK, "ok")
    }
}

#[async_trait]
impl ServeHttp for AuthProxy {
    async fn response(&self, http_session: &mut ServerSession) -> Response<Vec<u8>> {
        let path = http_session.req_header().uri.path().to_owned();
        let query = http_session
            .req_header()
            .uri
            .query()
            .unwrap_or("")
            .to_owned();
        let auth = http_session
            .req_header()
            .headers
            .get(http::header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .map(str::to_owned);

        match path.as_str() {
            "/healthz" => text_response(StatusCode::OK, "ok"),
            "/validate" => self.handle_validate(auth.as_deref(), &query).await,
            _ => {
                warn!(path = %path, "request for unknown path");
                text_response(StatusCode::NOT_FOUND, "not found")
            }
        }
    }
}

fn text_response(status: StatusCode, body: &str) -> Response<Vec<u8>> {
    Response::builder()
        .status(status)
        .header(http::header::CONTENT_TYPE, "text/plain")
        .body(body.as_bytes().to_vec())
        .unwrap()
}

fn must_env(key: &str) -> String {
    env::var(key).unwrap_or_else(|_| {
        eprintln!("required environment variable not set: {key}");
        std::process::exit(1);
    })
}

fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env())
        .init();

    let port = env::var("PORT").unwrap_or_else(|_| "8080".to_string());
    let addr = format!("0.0.0.0:{port}");
    info!("auth-proxy listening on {addr}");

    let mut server = Server::new(None).expect("failed to create server");
    server.bootstrap();

    let app = HttpServer::new_app(AuthProxy::from_env());
    let mut service = Service::new("auth-proxy".to_string(), app);
    service.add_tcp(&addr);
    server.add_service(service);

    server.run_forever();
}
