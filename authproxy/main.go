//
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
//

// auth-proxy is a lightweight Traefik ForwardAuth target that validates
// Bearer tokens via Keycloak's token introspection endpoint (RFC 7662)
// and enforces per-route scope requirements.
//
// Traefik calls: GET /validate?scope=<s1>&scope=<s2>
//   - 200 → token is active and has at least one of the listed scopes
//   - 401 → missing/inactive token
//   - 403 → token is valid but lacks the required scopes
package main

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

var (
	introspectURL    = mustEnv("TOKEN_INTROSPECTION_URL")
	introspectClient = mustEnv("INTROSPECT_CLIENT_ID")
	introspectSecret = mustEnv("INTROSPECT_CLIENT_SECRET")

	httpClient = &http.Client{Timeout: 5 * time.Second}
)

type introspectResponse struct {
	Active bool   `json:"active"`
	Scope  string `json:"scope"`
}

func main() {
	mux := http.NewServeMux()
	mux.HandleFunc("/validate", validateHandler)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	})

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	slog.Info("auth-proxy listening", "port", port)
	if err := http.ListenAndServe(":"+port, mux); err != nil {
		slog.Error("server exited", "error", err)
		os.Exit(1)
	}
}

func validateHandler(w http.ResponseWriter, r *http.Request) {
	authHeader := r.Header.Get("Authorization")
	if !strings.HasPrefix(authHeader, "Bearer ") {
		http.Error(w, "missing bearer token", http.StatusUnauthorized)
		return
	}
	rawToken := strings.TrimPrefix(authHeader, "Bearer ")

	result, err := introspect(rawToken)
	if err != nil {
		slog.Error("introspection error", "error", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	if !result.Active {
		http.Error(w, "token inactive", http.StatusUnauthorized)
		return
	}

	// Each ?scope= value is a candidate; token must carry at least one.
	requiredScopes := r.URL.Query()["scope"]
	if len(requiredScopes) > 0 {
		tokenScopes := strings.Fields(result.Scope)
		if !hasAnyScope(tokenScopes, requiredScopes) {
			slog.Warn("insufficient scope",
				"required", requiredScopes,
				"present", tokenScopes,
			)
			http.Error(w, "insufficient scope", http.StatusForbidden)
			return
		}
	}

	w.WriteHeader(http.StatusOK)
}

// introspect calls the Keycloak RFC 7662 introspection endpoint.
// Client credentials are sent in the POST body (not Basic Auth) because
// this Keycloak setup is configured with credentials_placement: body.
func introspect(token string) (*introspectResponse, error) {
	body := url.Values{
		"token":         []string{token},
		"client_id":     []string{introspectClient},
		"client_secret": []string{introspectSecret},
	}
	req, err := http.NewRequest(http.MethodPost, introspectURL, strings.NewReader(body.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	resp, err := httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	var result introspectResponse

	// Read raw response body for logging
	rawBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	if err := json.Unmarshal(rawBody, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// hasAnyScope returns true when tokenScopes contains at least one entry from required.
func hasAnyScope(tokenScopes, required []string) bool {
	have := make(map[string]struct{}, len(tokenScopes))
	for _, s := range tokenScopes {
		have[s] = struct{}{}
	}
	for _, r := range required {
		if _, ok := have[r]; ok {
			return true
		}
	}
	return false
}

func mustEnv(key string) string {
	v := os.Getenv(key)
	if v == "" {
		slog.Error("required environment variable not set", "key", key)
		os.Exit(1)
	}
	return v
}
