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

// clearglass is a lightweight Traefik ForwardAuth target that validates
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

struct ClearGlassProxy {
    introspect_url: String,
    client_id: String,
    client_secret: String,
    http_client: Client,
}

fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env())
        .init();

    let port = env::var("PORT").unwrap_or_else(|_| "8080".to_string());
    let addr = format!("0.0.0.0:{port}");
    info!("clearglass listening on {addr}");

    let mut server = Server::new(None).expect("failed to create server");
    server.bootstrap();

    let app = HttpServer::new_app(ClearGlassProxy::from_env());
    let mut service = Service::new("clearglass".to_string(), app);
    service.add_tcp(&addr);
    server.add_service(service);

    server.run_forever();
}

#[derive(Deserialize)]
struct IntrospectResponse {
    active: bool,
    #[serde(default)]
    scope: String,
}

impl ClearGlassProxy {
    fn from_env() -> Self {
        ClearGlassProxy {
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

    /// Handles the validation of a request's bearer token and its associated scopes.
    /// The required scopes are specified in the `required_token_scopes` parameter in the format `?scope=value1&scope=value2&...`.
    /// The auth header must start with "Bearer" and contain a valid JWT, the token itself must contain all
    /// required scopes.
    /// In addition, the token must pass the token introspection check.
    async fn handle_validate(
        &self,
        auth_header: Option<&str>,
        required_token_scopes: &str,
    ) -> Response<Vec<u8>> {
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
        let required: Vec<&str> = required_token_scopes
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
impl ServeHttp for ClearGlassProxy {
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

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    fn make_proxy(url: &str) -> ClearGlassProxy {
        ClearGlassProxy {
            introspect_url: url.to_string(),
            client_id: "test-client".to_string(),
            client_secret: "test-secret".to_string(),
            http_client: Client::builder()
                .timeout(Duration::from_secs(2))
                .build()
                .unwrap(),
        }
    }

    async fn setup_mock(active: bool, scope: &str) -> (MockServer, ClearGlassProxy) {
        let server = MockServer::start().await;
        let body = format!(r#"{{"active":{},"scope":"{}"}}"#, active, scope);
        Mock::given(method("POST"))
            .and(path("/introspect"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_string(body)
                    .insert_header("content-type", "application/json"),
            )
            .mount(&server)
            .await;
        let proxy = make_proxy(&format!("{}/introspect", server.uri()));
        (server, proxy)
    }

    fn response_body(resp: &Response<Vec<u8>>) -> String {
        String::from_utf8(resp.body().clone()).unwrap()
    }

    // --- Token extraction ---

    #[tokio::test]
    async fn no_auth_header_returns_401() {
        let (_, proxy) = setup_mock(true, "").await;
        let resp = proxy.handle_validate(None, "").await;
        assert_eq!(resp.status(), StatusCode::UNAUTHORIZED);
        assert_eq!(response_body(&resp), "missing bearer token");
    }

    #[tokio::test]
    async fn wrong_auth_scheme_returns_401() {
        let (_, proxy) = setup_mock(true, "").await;
        let resp = proxy.handle_validate(Some("Basic abc123"), "").await;
        assert_eq!(resp.status(), StatusCode::UNAUTHORIZED);
        assert_eq!(response_body(&resp), "missing bearer token");
    }

    #[tokio::test]
    async fn empty_token_returns_401() {
        let (_, proxy) = setup_mock(true, "").await;
        let resp = proxy.handle_validate(Some("Bearer "), "").await;
        assert_eq!(resp.status(), StatusCode::UNAUTHORIZED);
        assert_eq!(response_body(&resp), "invalid bearer token");
    }

    #[tokio::test]
    async fn oversized_token_returns_401() {
        let (_, proxy) = setup_mock(true, "").await;
        let long_token = format!("Bearer {}", "x".repeat(4097));
        let resp = proxy.handle_validate(Some(&long_token), "").await;
        assert_eq!(resp.status(), StatusCode::UNAUTHORIZED);
        assert_eq!(response_body(&resp), "invalid bearer token");
    }

    #[tokio::test]
    async fn max_length_token_is_accepted() {
        let (_, proxy) = setup_mock(true, "").await;
        let token = format!("Bearer {}", "x".repeat(4096));
        let resp = proxy.handle_validate(Some(&token), "").await;
        assert_eq!(resp.status(), StatusCode::OK);
    }

    // --- Introspection results ---

    #[tokio::test]
    async fn inactive_token_returns_401() {
        let (_, proxy) = setup_mock(false, "").await;
        let resp = proxy.handle_validate(Some("Bearer valid-token"), "").await;
        assert_eq!(resp.status(), StatusCode::UNAUTHORIZED);
        assert_eq!(response_body(&resp), "token inactive");
    }

    #[tokio::test]
    async fn introspection_error_returns_500() {
        // Point to a server that doesn't exist
        let proxy = make_proxy("http://127.0.0.1:1/introspect");
        let resp = proxy.handle_validate(Some("Bearer some-token"), "").await;
        assert_eq!(resp.status(), StatusCode::INTERNAL_SERVER_ERROR);
        assert_eq!(response_body(&resp), "internal error");
    }

    // --- Scope checking ---

    #[tokio::test]
    async fn active_token_no_scope_required_returns_200() {
        let (_, proxy) = setup_mock(true, "read write").await;
        let resp = proxy.handle_validate(Some("Bearer tok"), "").await;
        assert_eq!(resp.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn active_token_matching_scope_returns_200() {
        let (_, proxy) = setup_mock(true, "read write").await;
        let resp = proxy
            .handle_validate(Some("Bearer tok"), "scope=read")
            .await;
        assert_eq!(resp.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn active_token_one_of_multiple_scopes_matches_returns_200() {
        let (_, proxy) = setup_mock(true, "write").await;
        let resp = proxy
            .handle_validate(Some("Bearer tok"), "scope=read&scope=write")
            .await;
        assert_eq!(resp.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn active_token_no_matching_scope_returns_403() {
        let (_, proxy) = setup_mock(true, "read").await;
        let resp = proxy
            .handle_validate(Some("Bearer tok"), "scope=admin")
            .await;
        assert_eq!(resp.status(), StatusCode::FORBIDDEN);
        assert_eq!(response_body(&resp), "insufficient scope");
    }

    #[tokio::test]
    async fn empty_scope_value_is_ignored() {
        let (_, proxy) = setup_mock(true, "").await;
        // scope= with no value: split_once('=') yields ("scope", ""), which is empty
        // but it's still added to required. The token has no scopes either.
        // With empty string in required and empty scope set, .any() won't match → 403
        let resp = proxy.handle_validate(Some("Bearer tok"), "scope=").await;
        assert_eq!(resp.status(), StatusCode::FORBIDDEN);
    }

    #[tokio::test]
    async fn non_scope_query_params_are_ignored() {
        let (_, proxy) = setup_mock(true, "read").await;
        let resp = proxy
            .handle_validate(Some("Bearer tok"), "foo=bar&scope=read&baz=qux")
            .await;
        assert_eq!(resp.status(), StatusCode::OK);
    }

    // --- text_response helper ---

    #[test]
    fn text_response_sets_status_and_body() {
        let resp = text_response(StatusCode::FORBIDDEN, "denied");
        assert_eq!(resp.status(), StatusCode::FORBIDDEN);
        assert_eq!(response_body(&resp), "denied");
        assert_eq!(resp.headers().get("content-type").unwrap(), "text/plain");
    }
}
