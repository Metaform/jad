# JAD - Just Another Demonstrator

JAD is a demonstrator that deploys a fully-fledged dataspace as a Software-as-a-Service (SaaS) solution in Kubernetes.
This is to illustrate how Cloud Service Providers (CSPs) can deploy and manage dataspace components in their own cloud
infrastructure.

For that, JAD uses the "Virtual Connector" project: https://github.com/eclipse-edc/Virtual-Connector

Such a dataspace requires – at a minimum – the following components:

- a control plane: handles protocol messages and catalog data for each participant
- IdentityHub: responsible for managing Verifiable Credentials (presentation and storage)
- IssuerService: issues Verifiable Credentials to participants' IdentityHubs
- a data plane: performs the actual data transfer
- an identity provider: handles API authentication of management APIs. We are using Keycloak here.
- a vault: used to securely store sensitive data, such as the private keys etc. We are using Hashicorp Vault.
- a database server: contains persistent data of all the components. We are using PostgreSQL.
- a messaging system: used to process asynchronous messages. We are using NATS for this.

## Required tools and apps

- KinD: a basic Kubernetes runtime inside a single Docker container.
- Java 17+
- Docker
- `kubectl`
- macOS or Linux as an operating system. **Windows is not natively supported**!
- a POSIX-compliant shell (e.g., bash, zsh)
- Postman (or similar), or `newman`
- [optional]: a Kubernetes monitoring tool like K9S, Lens, Headlamp, etc. Not required, but certainly helpful.

_All shell commands are executed from the root of the project unless stated otherwise._

## Getting started

### 0. Create KinD cluster

To create a KinD cluster, run:

```shell
cp ~/.kube/config ~/.kube/config.bak # to save your existing kubeconfig
kind create cluster -n edcv --config kind.config.yaml --kubeconfig ~/.kube/edcv-kind.conf
ln -sf ~/.kube/edcv-kind.conf ~/.kube/config # to use KinD's kubeconfig
```

### 1. Build Docker images

To build the Docker images of the data space components, run:

```shell
./gradlew dockerize
```

This will build the Docker images for all components and store them in the local Docker registry.

JAD requires a special version of PostgreSQL. In particular, it install the `wal2json` extension. You can create this
special postgres version by running

```shell
docker buildx build -f launchers/postgres/Dockerfile --platform linux/amd64,linux/arm64 -t postgres:wal2json launchers/postgres
```

this will create the image `postgres:wal2json` for both amd64 and arm64 (e.g. Apple Silicon) architectures.

### 2. Load images into KinD

KinD has no access to the host's docker context, so we need to load the images into KinD. Verify that all images are
there by running `docker images`. Then run:

```shell
kind load docker-image \
    controlplane:0.15.0-SNAPSHOT \
    identity-hub:0.15.0-SNAPSHOT \
    issuerservice:0.15.0-SNAPSHOT \
    dataplane:0.15.0-SNAPSHOT \
    postgres:wal2json -n edcv
```

### 3. Deploy the services

JAD uses plain Kubernetes manifests to deploy the services and Kustomize to configure the order. All the manifests are
located in the [deployment](./deployment) folder.

```shell
kubectl apply -k deployment/
```

This deploys all the services in the correct order. The services are deployed in the `edcv` namespace. Please verify
that everything got deployed correctly by running `kubectl get deployments -n edcv`. This should output something like:

```text
NAME            READY   UP-TO-DATE   AVAILABLE   AGE
controlplane    1/1     1            1           66m
dataplane       1/1     1            1           66m
identityhub     1/1     1            1           66m
issuerservice   1/1     1            1           66m
keycloak        1/1     1            1           66m
nats            1/1     1            1           66m
postgres        1/1     1            1           66m
vault           1/1     1            1           66m
```

### 4. Inspect your deployment

- database: the PostgreSQL database is accessible from outside the cluster via
  `jdbc:postgresql://postgres.localhost/controlplane`, username `postgres`, password ``.
- vault: the vault is accessible from outside the cluster via `http://vault.localhost`, using token `root`.
- keycloak: access `http://keycloak.localhost/` and use username `admin` and password `admin`

### 5. Prepare the data space

On the dataspace level, a few bits and pieces are required for the data space to become operational. These can be put in
place by running the REST requests in the `Setup Issuer` folder in
the [Postman collection](./postman/Onboarding.postman_collection.json):

```shell
newman run \
  --folder "Setup Issuer" \
  --env-var "baseURL=http://localhost" \
  ./postman/Onboarding.postman_collection.json
```

This creates an issuer account and puts attestation definitions and credential definitions into the issuer's database.
It should output something like:

```text
EDC-V Onboarding

❏ Setup Issuer
↳ Create Tenant in IssuerService
  POST http://localhost/issuer/cs/api/identity/v1alpha/participants [200 OK, 351B, 92ms]
  ✓  Response is valid JSON
  ✓  Response contains apiKey, clientId and clientSecret

↳ Create AttestationDefinition
  POST http://localhost/issuer/admin/api/admin/v1alpha/participants/aXNzdWVy/attestations [201 Created, 204B, 9ms]

↳ Create CredentialDefinition
  POST http://localhost/issuer/admin/api/admin/v1alpha/participants/aXNzdWVy/credentialdefinitions [201 Created, 210B, 13ms]

┌─────────────────────────┬──────────────────┬──────────────────┐
│                         │         executed │           failed │
├─────────────────────────┼──────────────────┼──────────────────┤
│              iterations │                1 │                0 │
├─────────────────────────┼──────────────────┼──────────────────┤
│                requests │                3 │                0 │
├─────────────────────────┼──────────────────┼──────────────────┤
│            test-scripts │                4 │                0 │
├─────────────────────────┼──────────────────┼──────────────────┤
│      prerequest-scripts │                3 │                0 │
├─────────────────────────┼──────────────────┼──────────────────┤
│              assertions │                2 │                0 │
├─────────────────────────┴──────────────────┴──────────────────┤
│ total run duration: 159ms                                     │
├───────────────────────────────────────────────────────────────┤
│ total data received: 218B (approx)                            │
├───────────────────────────────────────────────────────────────┤
│ average response time: 38ms [min: 9ms, max: 92ms, s.d.: 38ms] │
└───────────────────────────────────────────────────────────────┘
```

Next, we need to create a consumer and a provider participant. For this, we can also use Postman:

```shell
newman run \
  --folder "Create Provider Tenant" \
  --folder "Create Consumer Tenant" \
  --env-var "baseURL=http://localhost" \
  ./postman/Onboarding.postman_collection.json
```

This sets up accounts in the IssuerService, the IdentityHub and the ControlPlane, plus it issues the
`MembershipCredential` to each new participant. It also seeds dummy data to each participant, specifically an Asset, a
Policy and a ContractDefinition. This is currently done from code, since EDC-V does not yet (Nov 7, 2025) have a
Management API.

The output should look like this:

```text
EDC-V Onboarding

❏ Create Consumer Tenant
↳ Create Consumer Holder in IssuerService
  POST http://localhost/issuer/admin/api/admin/v1alpha/participants/aXNzdWVy/holders [404 Not Found, 274B, 93ms]

↳ Create Consumer Tenant in IdentityHub
  POST http://localhost/cs/api/identity/v1alpha/participants [200 OK, 354B, 80ms]
  ✓  Response is valid JSON
  ✓  Response contains apiKey, clientId and clientSecret

↳ Request Credentials
  POST http://localhost/cs/api/identity/v1alpha/participants/Y29uc3VtZXI=/credentials/request [201 Created, 207B, 14ms]

↳ Create Consumer in Control Plane
  POST http://localhost/cp/api/mgmt/v1alpha/participants [201 Created, 564B, 7ms]

❏ Create Provider Tenant
↳ Create Provider Holder in IssuerService
  POST http://localhost/issuer/admin/api/admin/v1alpha/participants/aXNzdWVy/holders [404 Not Found, 274B, 6ms]

↳ Create Provider Tenant in IdentityHub
  POST http://localhost/cs/api/identity/v1alpha/participants [200 OK, 354B, 14ms]
  ✓  Response is valid JSON
  ✓  Response contains apiKey, clientId and clientSecret

↳ Request Credentials
  POST http://localhost/cs/api/identity/v1alpha/participants/cHJvdmlkZXI=/credentials/request [201 Created, 207B, 5ms]

↳ Create Provider in Control Plane
  POST http://localhost/cp/api/mgmt/v1alpha/participants [201 Created, 166B, 7ms]

┌─────────────────────────┬──────────────────┬──────────────────┐
│                         │         executed │           failed │
├─────────────────────────┼──────────────────┼──────────────────┤
│              iterations │                1 │                0 │
├─────────────────────────┼──────────────────┼──────────────────┤
│                requests │                8 │                0 │
├─────────────────────────┼──────────────────┼──────────────────┤
│            test-scripts │               18 │                0 │
├─────────────────────────┼──────────────────┼──────────────────┤
│      prerequest-scripts │               16 │                0 │
├─────────────────────────┼──────────────────┼──────────────────┤
│              assertions │                4 │                0 │
├─────────────────────────┴──────────────────┴──────────────────┤
│ total run duration: 336ms                                     │
├───────────────────────────────────────────────────────────────┤
│ total data received: 1.07kB (approx)                          │
├───────────────────────────────────────────────────────────────┤
│ average response time: 28ms [min: 5ms, max: 93ms, s.d.: 33ms] │
└───────────────────────────────────────────────────────────────┘
```

## Transfer Data

EDC-V does not yet have a Management API, so there is a quick'n'dirty workaround to transfer data: there is one API
endpoint that fetches the catalog (`Data Transfer/Get Catalog`) and another endpoint (`Data Transfer/Get Data`) that
initiates the contract negotiation, waits for its successful completion, then starts the data transfer.

Perform the entire sequence by running:

```shell
newman run --verbose \
  --folder "Get Catalog" \
  --folder "Get Data" \
  --env-var "baseURL=http://localhost" \
  ./postman/Onboarding.postman_collection.json
```

This will request the catalog, which contains exactly one dataset, then initiates contract negotiation and data
transfer for that asset. If everything went well, the output should contain demo output
from https://jsonplaceholder.typicode.com/todos, something like:

```json lines
[
  {
    "userId": 1,
    "id": 1,
    "title": "delectus aut autem",
    "completed": false
  },
  {
    "userId": 1,
    "id": 2,
    "title": "quis ut nam facilis et officia qui",
    "completed": false
  },
  //...
]
```

## Cleanup

To remove the deployment, run:

```shell
kubectl delete -k deployment/
```

## Experimental Features

### Management API using OAuth2.0 and Keycloak

Use the [create_user.sh](./create_user.sh) script to provision new clients in Keycloak:

```shell
./ ./create_user.sh consumer "Consumer Participant" participant
```

The output should look like this:

```shell
Getting admin access token...
Creating client consumer...
Client secret for consumer: JjG3hNm99h7b3JBxbAyqn8kPiCPiVkvQ
Assigned realm role 'participant' to service account for consumer
Adding client scopes to consumer as optional...
Client scopes added to consumer as optional
✅ Tenant client 'consumer' created successfully.
Use the following client credentials to obtain a token:
  Client UUID (internal, will be in the 'sub' claim): 0447f0bd-a451-4142-b68c-b710754def6b
  Client ID (used to get tokens):                     consumer
  Secret:                                             JjG3hNm99h7b3JBxbAyqn8kPiCPiVkvQ
  Participant Context ID:                             consumer
```

Take note of the "Client ID," the "Participant Context ID" and the "Secret"; we will need them to get an access token
from Keycloak.

The easiest way to do this is to set up authentication in Postman. In the "Authorization" tab, select "OAuth 2.0" and
configure the OAuth2.0 settings as follows:

![oauth_postman.png](oauth_postman.png)

Alternatively, you can manually fetch the access token by running:

```shell
curl -X POST http://keycloak.localhost/realms/edcv/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=consumer" \
  -d "client_secret=JjG3hNm99h7b3JBxbAyqn8kPiCPiVkvQ" | jq -r '.access_token'
```

Copy the access token and use it in the "Authorization" header using the "Bearer" prefix.

Use the requests in the `Experimental` folder in the [Postman collection](./postman/Onboarding.postman_collection.json)
to send requests to the Management API of EDC-V.