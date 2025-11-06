#!/usr/bin/env bash
set -euo pipefail

# -----------------------------
# CONFIGURATION
# -----------------------------
KC_HOST="${KC_HOST:-http://localhost:8080}"      # Keycloak base URL
REALM="${REALM:-edcv}"                          # Target realm
ADMIN_USER="${ADMIN_USER:-admin}"               # Keycloak Admin user for REST API
ADMIN_PASS="${ADMIN_PASS:-admin}"               # Keycloak Admin password
TENANT_CLIENT_ID="${1:-tenant-new}"             # ClientId for new tenant
TENANT_NAME="${2:-Tenant New}"                  # Client Name / display name
#TENANT_ROLE="${3:-participant}"                 # Realm role to assign
PARTICIPANT_CONTEXT_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"  # Generate UUID for participant context

# -----------------------------
# Get admin access token
# -----------------------------
echo "Getting admin access token..."
TOKEN=$(curl -s -X POST "$KC_HOST/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=$ADMIN_USER" \
  -d "password=$ADMIN_PASS" \
  -d "grant_type=password" \
  -d "client_id=admin-cli" | jq -r .access_token) # admin-cli is a built-in Keycloak user

# -----------------------------
# Create new confidential client
# -----------------------------
echo "Creating client $TENANT_CLIENT_ID..."
curl -s -X POST "$KC_HOST/admin/realms/$REALM/clients" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
        \"clientId\": \"$TENANT_CLIENT_ID\",
        \"name\": \"$TENANT_NAME\",
        \"description\": \"Tenant client for API access\",
        \"enabled\": true,
        \"secret\": \"$TENANT_CLIENT_ID\",
        \"protocol\": \"openid-connect\",
        \"publicClient\": false,
        \"serviceAccountsEnabled\": true,
        \"standardFlowEnabled\": false,
        \"directAccessGrantsEnabled\": false,
        \"fullScopeAllowed\": true,
        \"protocolMappers\": [
                    {
                      \"name\": \"participantContextId\",
                      \"protocol\": \"openid-connect\",
                      \"protocolMapper\": \"oidc-hardcoded-claim-mapper\",
                      \"consentRequired\": false,
                      \"config\": {
                        \"claim.name\": \"participant_context_id\",
                        \"claim.value\": \"$PARTICIPANT_CONTEXT_ID\",
                        \"jsonType.label\": \"String\",
                        \"access.token.claim\": \"true\",
                        \"id.token.claim\": \"true\",
                        \"userinfo.token.claim\": \"true\"
                      }
                    },
                    {
                      \"name\": \"role\",
                      \"protocol\": \"openid-connect\",
                      \"protocolMapper\": \"oidc-hardcoded-claim-mapper\",
                      \"consentRequired\": false,
                      \"config\": {
                        \"claim.name\": \"role\",
                        \"claim.value\": \"participant\",
                        \"jsonType.label\": \"String\",
                        \"access.token.claim\": \"true\",
                        \"id.token.claim\": \"true\",
                        \"userinfo.token.claim\": \"true\"
                      }
                    }
                  ]
      }"

# -----------------------------
# Get internal client UUID
# -----------------------------
CLIENT_UUID=$(curl -s -X GET "$KC_HOST/admin/realms/$REALM/clients?clientId=$TENANT_CLIENT_ID" \
  -H "Authorization: Bearer $TOKEN" | jq -r '.[0].id')

# -----------------------------
# Retrieve client secret
# -----------------------------
SECRET=$(curl -s -X POST "$KC_HOST/admin/realms/$REALM/clients/$CLIENT_UUID/client-secret" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" | jq -r .value)

echo "Client secret for $TENANT_CLIENT_ID: $SECRET"

## -----------------------------
## Get service account user ID
## -----------------------------
#SERVICE_ACCOUNT_ID=$(curl -s -X GET "$KC_HOST/admin/realms/$REALM/clients/$CLIENT_UUID/service-account-user" \
#  -H "Authorization: Bearer $TOKEN" | jq -r .id)
#
## -----------------------------
## Get realm role ID
## -----------------------------
#ROLE_ID=$(curl -s -X GET "$KC_HOST/admin/realms/$REALM/roles/$TENANT_ROLE" \
#  -H "Authorization: Bearer $TOKEN" | jq -r .id)
#
## -----------------------------
## Assign realm role to service account
## -----------------------------
#curl -s -X POST "$KC_HOST/admin/realms/$REALM/users/$SERVICE_ACCOUNT_ID/role-mappings/realm" \
#  -H "Authorization: Bearer $TOKEN" \
#  -H "Content-Type: application/json" \
#  -d "[{\"id\":\"$ROLE_ID\",\"name\":\"$TENANT_ROLE\"}]"
#
#echo "Assigned realm role '$TENANT_ROLE' to service account for $TENANT_CLIENT_ID"

# -----------------------------
# Add all client scopes to the new client as optional
# -----------------------------
echo "Adding client scopes to $TENANT_CLIENT_ID as optional..."
CLIENT_SCOPES=$(curl -s -X GET "$KC_HOST/admin/realms/$REALM/client-scopes" \
  -H "Authorization: Bearer $TOKEN" | jq -r '.[].id')

for SCOPE_ID in $CLIENT_SCOPES; do
  curl -s -X PUT "$KC_HOST/admin/realms/$REALM/clients/$CLIENT_UUID/optional-client-scopes/$SCOPE_ID" \
    -H "Authorization: Bearer $TOKEN"
done

echo "Client scopes added to $TENANT_CLIENT_ID as optional"

# -----------------------------
# Optional: add tenant_id claim
# -----------------------------
# we are setting protocol mappers directly when creating the user, see above.

#if [ -n "$TENANT_ID_CLAIM" ]; then
#  echo "Adding protocol mapper for tenant_id claim..."
#  curl -s -X POST "$KC_HOST/admin/realms/$REALM/clients/$CLIENT_UUID/protocol-mappers/models" \
#    -H "Authorization: Bearer $TOKEN" \
#    -H "Content-Type: application/json" \
#    -d "{
#          \"name\": \"tenant_id\",
#          \"protocol\": \"openid-connect\",
#          \"protocolMapper\": \"oidc-hardcoded-claim-mapper\",
#          \"consentRequired\": false,
#          \"config\": {
#            \"claim.name\": \"tenant_id\",
#            \"claim.value\": \"$TENANT_ID_CLAIM\",
#            \"jsonType.label\": \"String\",
#            \"access.token.claim\": \"true\",
#            \"id.token.claim\": \"true\",
#            \"userinfo.token.claim\": \"true\"
#          }
#        }"
#  echo "tenant_id claim added with value '$TENANT_ID_CLAIM'"
#fi

echo "✅ Tenant client '$TENANT_CLIENT_ID' created successfully."
echo "Use the following client credentials to obtain a token:"
echo "  Client UUID (internal, will be in the 'sub' claim): $CLIENT_UUID"
echo "  Client ID (used to get tokens):                     $TENANT_CLIENT_ID"
echo "  Secret:                                              $SECRET"
echo "  Participant Context ID:                              $PARTICIPANT_CONTEXT_ID"