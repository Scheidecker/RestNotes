# Google Cloud Platform deployment
Author: Carlos Scheidecker
Provider guidance reviewed: 2026-10-17.

## Scope, safety and deployment model
This is a step-by-step **deployment runbook**, not evidence that a cloud deployment has been executed. Commands create billable resources and change IAM; execute them only in an approved project after reviewing organization policies, costs and the resulting configuration. Examples use Bash from the repository's `API` directory. Replace every `REPLACE_...` value before use. Never enable shell tracing or put secret values in command-line literals, Git, images or deployment YAML.

Target: Artifact Registry → Cloud Run service (RestNotes + Cloud SQL Auth Proxy sidecar) → private-IP Cloud SQL MySQL, with Secret Manager and Cloud Logging. A separate single-task Cloud Run job runs Liquibase before rollout. The initial service requires Cloud Run IAM as well as RestNotes authentication; public exposure is a separate security decision.

The application already supports MySQL JDBC, production profiles and PEM key files. It does **not** include the Cloud SQL Java Connector or a migration-only application command. This guide chooses a TCP Auth Proxy instead of pretending a plain JDBC URL can use `/cloudsql` Unix sockets. No application-source change is required for that choice; the migration image/wrapper below are release tooling to prepare and test, not files already supplied by the repository.

Read [architecture](architecture.md), [local requirements](system-requirements.md), [configuration](../README.md#configuration) and [schema reference](Database-Schema-Reference.md) first. Production deployment does not complete the deferred application features or the security improvements listed in the README.

## 1. Prepare the project, workstation and release
1. Use an approved GCP project with billing, budget alerts, a data-residency decision and quota for Cloud Run, Cloud SQL, private services access and Artifact Registry. Start in staging.
2. Install current Google Cloud CLI, Docker/buildx, Git, JDK 25, unzip, curl, Python 3 and OpenSSL. Use federated/user credentials or workload identity; do not download long-lived service-account JSON keys.
3. Authenticate using your organization's login procedure. A provisioning administrator needs the relevant network/SQL/secret/IAM creation permissions; the release deployer should only deploy Run resources, push/read release images and act as the selected runtime/migration service accounts. Do not grant Owner to workloads.
4. Choose a dedicated region and non-overlapping CIDRs. These example names are non-secret configuration:

```bash
export RN_PROJECT='REPLACE_PROJECT_ID'
export RN_REGION='us-central1'
export RN_NETWORK='restnotes-vpc'
export RN_SUBNET='restnotes-run'
export RN_SQL='restnotes-mysql'
export RN_SERVICE='restnotes-api'
export RN_JOB='restnotes-migrate'
export RN_REPOSITORY='restnotes'
export RN_RUNTIME_SA="restnotes-runtime@${RN_PROJECT}.iam.gserviceaccount.com"
export RN_MIGRATION_SA="restnotes-migration@${RN_PROJECT}.iam.gserviceaccount.com"
gcloud config set project "$RN_PROJECT"
gcloud auth list --filter=status:ACTIVE --format='value(account)'
./mvnw --batch-mode --no-transfer-progress clean verify
export RN_RELEASE="$(git rev-parse --short=12 HEAD)"
export RN_REGISTRY="${RN_REGION}-docker.pkg.dev/${RN_PROJECT}/${RN_REPOSITORY}"
export RN_RELEASE_DIR="$(mktemp -d)"
```

The release tag identifies a reviewed commit; also require a clean/reviewed working tree. Store your release manifest outside the source tree with image digests, managed MySQL version, migration IDs, configuration and secret **version references**, never secret contents.

## 2. Enable services and create an image repository

```bash
gcloud services enable run.googleapis.com sqladmin.googleapis.com \
  artifactregistry.googleapis.com secretmanager.googleapis.com \
  compute.googleapis.com servicenetworking.googleapis.com \
  logging.googleapis.com monitoring.googleapis.com
gcloud artifacts repositories create "$RN_REPOSITORY" \
  --repository-format=docker --location="$RN_REGION"
gcloud auth configure-docker "${RN_REGION}-docker.pkg.dev"
```

If resources already exist, inspect/reuse the approved resources rather than recreating or deleting them. Grant the deployer Artifact Registry Writer on this repository. Verify the Cloud Run service agent can read images, especially across projects. Keep artifact access scoped, scan images, and retain previous release digests for rollback.

## 3. Create private networking
Use a custom VPC and a regional subnet for Direct VPC egress. The sample ranges are examples: replace them if they overlap existing networks.

```bash
gcloud compute networks create "$RN_NETWORK" --subnet-mode=custom
gcloud compute networks subnets create "$RN_SUBNET" \
  --network="$RN_NETWORK" --region="$RN_REGION" \
  --range=10.40.0.0/24 --enable-private-ip-google-access
gcloud compute addresses create restnotes-sql-range --global \
  --purpose=VPC_PEERING --prefix-length=16 --network="$RN_NETWORK"
gcloud services vpc-peerings connect --service=servicenetworking.googleapis.com \
  --ranges=restnotes-sql-range --network="$RN_NETWORK"
```

Direct VPC egress needs a `/26` or larger subnet; budget additional IPs for overlapping revisions and retained allocations. Private services access uses its own allocated range. Verify peering completes before database creation.

Review firewall/organization policies: proxy traffic needs private connectivity to Cloud SQL on TCP **3307**, plus DNS and HTTPS access to required Google APIs. Administrative direct-MySQL access uses 3306 and must be separately restricted. The proxy does not create routes. This guide uses `private-ranges-only` egress, so public API traffic is not forced through a NAT. An `all-traffic` design needs explicitly configured Private Google Access/NAT and egress policy. Shared VPC requires the documented service-agent/subnet permissions.

## 4. Create Cloud SQL and least-privilege database identities
In Cloud SQL Console:
1. Create a **MySQL 8.4** instance in RN_REGION. Prefer regional HA for production; a smaller zonal instance is only a staging/cost trade-off.
2. Select RN_NETWORK and **private IP only** using the private services connection. Do not add public authorized networks.
3. Enable storage encryption, deletion protection, automated backups and point-in-time recovery/binlogs; set retention and maintenance windows. Review storage autoscaling, availability and database flags before creation.
4. Use utf8mb4 / utf8mb4_0900_ai_ci for RestNotes. Verify managed flags and SQL behavior against the existing schema rather than rewriting migrations.
5. Create database `RestNotes`. Cloud SQL, not Liquibase, provisions the database and users.
6. Create distinct `restnotes_app` and `restnotes_migrator` built-in database users with independent generated passwords using approved administrative tooling. Save each password directly in a password manager/Secret Manager without displaying or logging it.
7. Inspect SHOW GRANTS and remove inherited/broad privileges, including any default administrative role such as cloudsqlsuperuser. Give the application only schema DML; give the migrator schema DDL/DML. Do not use root or the instance administrator in either workload.

The intended grants, after the DBA has removed broader grants/roles, are:

```sql
-- Author: Carlos Scheidecker
GRANT SELECT, INSERT, UPDATE, DELETE ON `RestNotes`.* TO 'restnotes_app'@'%';
GRANT ALL PRIVILEGES ON `RestNotes`.* TO 'restnotes_migrator'@'%';
SHOW GRANTS FOR 'restnotes_app'@'%';
SHOW GRANTS FOR 'restnotes_migrator'@'%';
```

These GRANT statements do not remove existing privileges. Audit the final effective grants. SQL administrative access should use an approved private runner/proxy, never an opened public database port.

```bash
export RN_SQL_CONNECTION="$(gcloud sql instances describe "$RN_SQL" --format='value(connectionName)')"
gcloud sql instances describe "$RN_SQL" \
  --format='yaml(databaseVersion,settings.availabilityType,settings.backupConfiguration,ipAddresses)'
```

**Version gate:** local tests pin MySQL 8.4.11; managed Cloud SQL selects available maintenance builds and may run a different 8.4 patch. Record `SELECT VERSION()` in staging and rehearse migrations, role caps, collation, ETags, races and leases on that version. Do not assume MYSQL_8_4 means exactly 8.4.11 or substitute MySQL 8.0/MariaDB without a separate compatibility review.

## 5. Create workload service accounts and secrets

```bash
gcloud iam service-accounts create restnotes-runtime
gcloud iam service-accounts create restnotes-migration
for sa in "$RN_RUNTIME_SA" "$RN_MIGRATION_SA"; do
  gcloud projects add-iam-policy-binding "$RN_PROJECT" \
    --member="serviceAccount:${sa}" --role=roles/cloudsql.client
done
```

Cloud SQL Client authorizes the proxy tunnel; it does not replace database-user authentication or grant SQL privileges.

Create Secret Manager secrets `restnotes-db-password`, `restnotes-migration-password`, `restnotes-jwt-private` and `restnotes-jwt-public`. Enter the two already-created database passwords through approved secret-management tooling. Generate one matching application RSA pair in a protected temporary directory outside Git:

```bash
export RN_KEY_DIR="$(mktemp -d)"
chmod 700 "$RN_KEY_DIR"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 \
  -out "$RN_KEY_DIR/private.pem"
chmod 600 "$RN_KEY_DIR/private.pem"
openssl pkey -in "$RN_KEY_DIR/private.pem" -pubout -out "$RN_KEY_DIR/public.pem"
gcloud secrets create restnotes-jwt-private --replication-policy=automatic \
  --data-file="$RN_KEY_DIR/private.pem"
gcloud secrets create restnotes-jwt-public --replication-policy=automatic \
  --data-file="$RN_KEY_DIR/public.pem"
```

For existing secrets, add versions instead of recreating them. Do not print key files. Restrict/clean temporary key files under your approved secret-handling procedure and retain a secured recovery copy only if required.

Grant runtime Secret Accessor on the app password and both JWT secrets, not the migration password. Grant migration Secret Accessor only on its database password:

```bash
for secret in restnotes-db-password restnotes-jwt-private restnotes-jwt-public; do
  gcloud secrets add-iam-policy-binding "$secret" \
    --member="serviceAccount:${RN_RUNTIME_SA}" --role=roles/secretmanager.secretAccessor
done
gcloud secrets add-iam-policy-binding restnotes-migration-password \
  --member="serviceAccount:${RN_MIGRATION_SA}" --role=roles/secretmanager.secretAccessor
```

Pin numeric secret versions in release configuration. Both replicas and overlapping revisions must use the same matching signing pair. Secret volume files are root-owned in gen2; choose a mode readable by UID 10001, not root-only 0400. Mount the private key only into the application container. The key loader reads keys once at startup: changing a secret does not implement coordinated live rotation.

## 6. Build immutable application and migration images
Build/push the application, then record the registry digest:

```bash
docker buildx build --platform linux/amd64 --push \
  --tag "$RN_REGISTRY/api:$RN_RELEASE" .
gcloud artifacts docker images describe "$RN_REGISTRY/api:$RN_RELEASE" \
  --format='value(image_summary.fully_qualified_digest)'
./mvnw --batch-mode --no-transfer-progress dependency:copy-dependencies \
  -DincludeArtifactIds=mysql-connector-j -DoutputDirectory="$RN_RELEASE_DIR/drivers"
mkdir -p "$RN_RELEASE_DIR/changelog"
cp -R src/main/resources/db/changelog/. "$RN_RELEASE_DIR/changelog/"
```

Use the returned `api@sha256:...` reference below. Liquibase Community 5 does not guarantee bundled JDBC drivers: copy the Boot-resolved MySQL driver, not an unrelated latest driver.

Create the following release-tooling files inside RN_RELEASE_DIR, not in a directory containing secret files. Resolve/record approved amd64 image digests for `liquibase/liquibase:5.0.4` and a reviewed Cloud SQL Auth Proxy v2 release (for example the documented 2.26.x line); replace the ARG values with those digest references before building.

`Dockerfile.migrate`:

```dockerfile
# Author: Carlos Scheidecker
ARG PROXY_IMAGE
ARG LIQUIBASE_IMAGE
FROM ${PROXY_IMAGE} AS proxy
FROM ${LIQUIBASE_IMAGE}
COPY --from=proxy /cloud-sql-proxy /usr/local/bin/cloud-sql-proxy
COPY --chmod=0444 drivers/*.jar /liquibase/lib/
COPY --chmod=0555 changelog/ /liquibase/changelog/db/changelog/
COPY --chmod=0555 migrate.sh /liquibase/migrate.sh
ENTRYPOINT ["/bin/bash", "/liquibase/migrate.sh"]
```

`migrate.sh`:

```bash
#!/bin/bash
# Author: Carlos Scheidecker
set -euo pipefail
: "${INSTANCE_CONNECTION_NAME:?}" "${LIQUIBASE_COMMAND_URL:?}"
: "${LIQUIBASE_COMMAND_USERNAME:?}" "${LIQUIBASE_COMMAND_PASSWORD:?}"
/usr/local/bin/cloud-sql-proxy --private-ip --address=127.0.0.1 --port=3306 \
  --health-check --http-address=127.0.0.1 --http-port=9090 \
  "$INSTANCE_CONNECTION_NAME" &
proxy_pid=$!
trap 'kill "$proxy_pid" 2>/dev/null || true; wait "$proxy_pid" 2>/dev/null || true' EXIT
ready=false
for attempt in {1..90}; do
  if (exec 3<>/dev/tcp/127.0.0.1/9090
      printf 'GET /readiness HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
      IFS= read -r status <&3
      [[ "$status" == *" 200 "* ]]) 2>/dev/null; then
    ready=true
    break
  fi
  kill -0 "$proxy_pid"
  sleep 2
done
test "$ready" = true
liquibase --search-path=/liquibase/changelog \
  --changelog-file=db/changelog/schema.yml update
```

Both image ARGs are global, before the first FROM. Use approved digest values, then build/push the tooling image:

```bash
export RN_PROXY_IMAGE='REPLACE_APPROVED_PROXY_IMAGE_AT_SHA256'
export RN_LIQUIBASE_IMAGE='REPLACE_LIQUIBASE_5_0_4_IMAGE_AT_SHA256'
docker buildx build --platform linux/amd64 --push \
  --build-arg PROXY_IMAGE="$RN_PROXY_IMAGE" \
  --build-arg LIQUIBASE_IMAGE="$RN_LIQUIBASE_IMAGE" \
  -f "$RN_RELEASE_DIR/Dockerfile.migrate" \
  --tag "$RN_REGISTRY/migrate:$RN_RELEASE" "$RN_RELEASE_DIR"
gcloud artifacts docker images describe "$RN_REGISTRY/migrate:$RN_RELEASE" \
  --format='value(image_summary.fully_qualified_digest)'
```

The wrapper owns/stops its proxy child so the job can exit; an always-running sidecar alone would leave a migration job unfinished. Rehearse the image on a disposable schema before using it in production. Preserve changelog identity `db/changelog/schema.yml`; do not apply SQL files manually or change historical paths/checksums.
The proxy binary is taken from the same approved image as the service sidecar. The schema/driver files are intentionally readable by the CLI's non-root user regardless of the workstation's umask. Verify that the chosen CLI image has Bash and all required YAML/parser dependencies before publishing.

## 7. Run schema-only migration once
In Cloud Run → Jobs → Create:
1. Select the **migration image digest**, RN_REGION, RN_MIGRATION_SA, one task, parallelism 1, retries 0 and a reviewed timeout (initially 10 minutes).
2. Configure Direct VPC egress to RN_NETWORK/RN_SUBNET, private ranges only.
3. Set `INSTANCE_CONNECTION_NAME` to RN_SQL_CONNECTION, `LIQUIBASE_COMMAND_USERNAME=restnotes_migrator`, and `LIQUIBASE_COMMAND_URL=jdbc:mysql://127.0.0.1:3306/RestNotes?sslMode=DISABLED&allowPublicKeyRetrieval=true`.
4. Inject `LIQUIBASE_COMMAND_PASSWORD` from the pinned migration secret version. Set `LIQUIBASE_ANALYTICS_ENABLED=false`.
5. Use the image's entrypoint unchanged. No JWT keys or application prod profile are needed by this CLI image. It runs schema.yml only, never demo.yml.
6. Execute and wait for success:

```bash
gcloud run jobs execute "$RN_JOB" --region="$RN_REGION" --wait
```

The disabled JDBC TLS setting applies **only to loopback inside the workload**; the proxy authenticates/encrypts its remote Cloud SQL connection. Public-key retrieval is likewise limited to that trusted loopback MySQL 8.4 authentication hop. Never copy this URL to a direct network connection.

Confirm DATABASECHANGELOG contains 001, 002, 004 and 005 with expected identities, no 003 demo entry, and no demo rows. Verify table names/constraints and migration lock release. If a migration fails, stop rollout and inspect actual schema plus execution records: MySQL DDL is not universally transactional. Do not clear checksums/locks or retry partial DDL blindly. Do not start the application image with `MIGRATIONS_ENABLED=true` and call that a terminating migration job.

## 8. Configure and deploy the Cloud Run service
Save the following as `$RN_RELEASE_DIR/service.yaml`, outside the source tree (or maintain a reviewed copy in an approved non-secret infrastructure repository). It is a template: replace all placeholders, including **numeric** secret versions and image digests. If you changed RN_SERVICE, update metadata.name too. Start with restricted IAM invocation; do not add allUsers.

```yaml
# Author: Carlos Scheidecker
apiVersion: serving.knative.dev/v1
kind: Service
metadata:
  name: restnotes-api
  annotations:
    run.googleapis.com/ingress: all
spec:
  template:
    metadata:
      annotations:
        run.googleapis.com/execution-environment: gen2
        run.googleapis.com/cpu-throttling: "false"
        run.googleapis.com/container-dependencies: '{"api":["sql-proxy"]}'
        run.googleapis.com/network-interfaces: '[{"network":"REPLACE_NETWORK","subnetwork":"REPLACE_SUBNET"}]'
        run.googleapis.com/vpc-access-egress: private-ranges-only
        autoscaling.knative.dev/minScale: "1"
        autoscaling.knative.dev/maxScale: "3"
    spec:
      serviceAccountName: REPLACE_RUNTIME_SA_EMAIL
      containerConcurrency: 20
      timeoutSeconds: 60
      containers:
        - name: api
          image: REPLACE_API_IMAGE_AT_SHA256
          ports:
            - name: http1
              containerPort: 8081
          resources:
            limits: {cpu: "1", memory: 1Gi}
          env:
            - {name: SERVER_PORT, value: "8081"}
            - {name: SPRING_PROFILES_ACTIVE, value: prod}
            - {name: DEMO_DATA_ENABLED, value: "false"}
            - {name: MIGRATIONS_ENABLED, value: "false"}
            - {name: REGISTRATION_ENABLED, value: "false"}
            - {name: OPENAPI_ENABLED, value: "false"}
            - {name: DB_USERNAME, value: restnotes_app}
            - {name: DB_URL, value: "jdbc:mysql://127.0.0.1:3306/RestNotes?sslMode=DISABLED&allowPublicKeyRetrieval=true"}
            - {name: DB_POOL_SIZE, value: "5"}
            - {name: JWT_ISSUER, value: RestNotes}
            - {name: JWT_AUDIENCE, value: RestNotes-api}
            - {name: JWT_TTL_SECONDS, value: "900"}
            - {name: JWT_PRIVATE_KEY_LOCATION, value: "file:/run/restnotes-private/private.pem"}
            - {name: JWT_PUBLIC_KEY_LOCATION, value: "file:/run/restnotes-public/public.pem"}
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef: {name: restnotes-db-password, key: "REPLACE_NUMERIC_VERSION"}
          volumeMounts:
            - {name: jwt-private, mountPath: /run/restnotes-private}
            - {name: jwt-public, mountPath: /run/restnotes-public}
          startupProbe:
            httpGet: {path: /actuator/health/readiness, port: 8081}
            periodSeconds: 5
            timeoutSeconds: 3
            failureThreshold: 48
          livenessProbe:
            httpGet: {path: /actuator/health/liveness, port: 8081}
            periodSeconds: 30
            timeoutSeconds: 3
        - name: sql-proxy
          image: REPLACE_APPROVED_PROXY_IMAGE_AT_SHA256
          args:
            - --private-ip
            - --address=127.0.0.1
            - --port=3306
            - --health-check
            - --http-address=0.0.0.0
            - --http-port=9090
            - REPLACE_INSTANCE_CONNECTION_NAME
          resources:
            limits: {cpu: "1", memory: 256Mi}
          startupProbe:
            httpGet: {path: /startup, port: 9090}
            periodSeconds: 2
            timeoutSeconds: 1
            failureThreshold: 60
      volumes:
        - name: jwt-private
          secret:
            secretName: restnotes-jwt-private
            defaultMode: 292
            items: [{key: "REPLACE_NUMERIC_VERSION", path: private.pem}]
        - name: jwt-public
          secret:
            secretName: restnotes-jwt-public
            defaultMode: 292
            items: [{key: "REPLACE_NUMERIC_VERSION", path: public.pem}]
```

Mode 292 is octal 0444: root-owned gen2 secret files remain readable by the non-root API. Secret access still requires IAM and the private key is mounted only into api. No proxy SQL/health port is the ingress port. Explicit 8081 is necessary because the Dockerfile already defines SERVER_PORT, which takes precedence over Cloud Run's PORT fallback.

```bash
gcloud run services replace "$RN_RELEASE_DIR/service.yaml" --region="$RN_REGION"
export RN_BASE_URL="$(gcloud run services describe "$RN_SERVICE" \
  --region="$RN_REGION" --format='value(status.url)')"
```

Inspect the effective IAM policy: ingress `all` allows reaching the platform endpoint, but does not itself grant invoker access. Ensure no inherited/allUsers public invocation or disabled invoker-IAM check undermines the private starting posture. Grant `roles/run.invoker` only to approved operators/callers.

Tune resource limits only after load testing. The example allocates CPU continuously, includes two containers and a minimum instance, so it has standing cost. Ensure peak old+new instances × DB_POOL_SIZE plus migration/admin headroom stays below the managed database's connection budget; per-revision limits and temporary surge are not a strict global connection cap. Direct VPC cold-start delay can exceed a minute: test startup and datasource initialization budgets, not just probe duration.

## 9. Verify TLS, platform IAM and application behavior
Cloud Run supplies HTTPS on its run.app URL. A private service requires a Google ID token in **X-Serverless-Authorization**, leaving Authorization available for RestNotes JWTs. Do not send the Google token as the application's bearer token.

For a user operator with Invoker permission:

```bash
export RN_PLATFORM_TOKEN="$(gcloud auth print-identity-token)"
printf 'X-Serverless-Authorization: Bearer %s\n' "$RN_PLATFORM_TOKEN" |
  curl --fail-with-body --header @- "$RN_BASE_URL/actuator/health/readiness"
printf 'X-Serverless-Authorization: Bearer %s\n' "$RN_PLATFORM_TOKEN" |
  curl --silent --show-error --output /dev/null --write-out '%{http_code}\n' \
    --header @- "$RN_BASE_URL/v3/api-docs"
```

Expect UP for readiness and application 401/403 for disabled docs, not a Swagger page. For workload impersonation use an approved invoker service account and a Google-signed ID token with audience equal to RN_BASE_URL. Refresh platform credentials as required; they are distinct from RestNotes JWTs.

Fresh schema-only databases have **no application users**. Bootstrap registration only during an approved private invocation window: temporarily set REGISTRATION_ENABLED=true on api, create real accounts and teams using the [CURL guide](CURL_Guide.md), then restore false and verify registration returns 403. Add the platform header to every CURL request, alongside the RestNotes Authorization header for protected routes. Capture both token types without printing them:

```bash
printf 'X-Serverless-Authorization: Bearer %s\nAuthorization: Bearer %s\n' \
  "$RN_PLATFORM_TOKEN" "$RN_TOKEN" |
  curl --fail-with-body --header @- "$RN_BASE_URL/api/v1/users/me"
```

Do not deploy demo.yml or insert a guessed password hash to create an admin. Team creation makes its authenticated creator a team ADMIN; there is no global administrator bootstrap endpoint.

Verify owner/viewer/shared-role behavior, stale 412, missing 428, locked 423, expired-token 409, a note/tag write and archive, plus the absence of demo fixtures. Confirm all replicas accept the same JWT, and lease/content state survives an instance restart. Do not publish notes/tokens in smoke-test logs.

For public browser/API access, first add an approved external HTTPS Application Load Balancer/serverless NEG and Cloud Armor/rate controls, restrict Cloud Run ingress to internal-and-cloud-load-balancing to prevent bypass, and deliberately design Invoker/public or IAP policy. Do not simply grant allUsers and assume Spring supplies edge throttling, MFA or abuse protection. A custom domain needs validated DNS/TLS and retesting of the chosen platform-auth audience.

## 10. Observe, back up and rotate
- Review Cloud Logging locally; redact note data/credentials before sharing. Alert on readiness failures, 5xx, auth-abuse trends, connection saturation, memory kills, deadlocks and Cloud SQL storage/replication/backups.
- Retain audited configuration/image/secret-version references. Never log resolved environment or key/secret payloads.
- Test point-in-time/snapshot restore into a **new staging instance**, reapply least-privilege users/settings as needed, and validate migrations/data/tenant isolation before any cutover. Define RPO/RTO and a write-freeze procedure.
- Rotate DB credentials through the database and Secret Manager coherently; deploy new revisions so environment secrets refresh. Signing-key replacement invalidates existing JWTs because overlapping verification keys are not implemented. Coordinate both key versions/replicas and user re-login. Disable old secret versions only after rollback requirements are addressed.

## 11. Roll forward or roll back safely
Record the last healthy revision and image/configuration before deployment. After a migration succeeds, deploy and test a new revision in staging; for later production releases use a no-traffic/tagged candidate or controlled traffic rollout rather than immediately replacing all traffic.

An application rollback can restore traffic to a compatible prior revision:

```bash
export RN_PREVIOUS_REVISION='REPLACE_HEALTHY_REVISION'
gcloud run services update-traffic "$RN_SERVICE" --region="$RN_REGION" \
  --to-revisions="${RN_PREVIOUS_REVISION}=100"
```

That does not reverse schema/data changes. Old code must tolerate the new schema, keys and secret versions. Current SQL-file changesets have no automatic rollback contract; MySQL DDL may be partially committed. Use additive expand/contract migrations, a reviewed forward fix or a backed-up restore/cutover plan. Restoring the database can discard writes made since the restore point and can also restore leases/history; reconcile that operational state before reopening writes.

## 12. Cost control and deliberate cleanup
Cloud SQL/HA storage/backups, minimum Cloud Run instances, continuous sidecar CPU, Artifact Registry, logs, Secret Manager and optional load balancer/Cloud Armor incur charges. Budgets alert; they do not automatically cap spending. Scaling Cloud Run to zero does not stop database or retained-storage costs.

For a disposable staging environment only, first export required release/audit records and take/verify a final backup. Then remove the service and migration job, explicitly review database deletion protection and retained backups before any SQL-instance deletion, and remove only dedicated artifacts/secrets/network resources no longer needed. Private-service peering, shared subnets, IAM principals and secrets may serve other workloads: never delete them broadly. Direct VPC subnet allocation can take time to release.

Do not delete a whole project as a cleanup shortcut. No destructive database/project cleanup command is provided to avoid accidental loss of production data. Finish by reviewing billing and remaining resources, not just the Cloud Run service list.

## References and validation boundary
Provider commands/templates were researched, not executed against a GCP account by this documentation change. CLI versions, organization policies, actual MySQL patch, image digests and secret versions must be checked in the target project. A staging rehearsal is a release gate.

- [Cloud Run container/sidecar configuration](https://cloud.google.com/run/docs/configuring/services/containers)
- [Direct VPC egress](https://cloud.google.com/run/docs/configuring/vpc-direct-vpc)
- [Cloud SQL private services access](https://cloud.google.com/sql/docs/mysql/configure-private-services-access)
- [Cloud SQL Auth Proxy, supported flags and TCP/MySQL 8.4 constraints](https://github.com/GoogleCloudPlatform/cloud-sql-proxy)
- [Cloud Run secrets and volume ownership](https://cloud.google.com/run/docs/configuring/services/secrets)
- [Cloud Run jobs](https://cloud.google.com/run/docs/create-jobs)
- [Cloud Run ID tokens and X-Serverless-Authorization](https://cloud.google.com/run/docs/authenticating/service-to-service)
- [Cloud SQL MySQL 8.4 maintenance versions](https://cloud.google.com/sql/docs/mysql/maintenance-changelog/mysql-8-4)
- [Liquibase changelog identity/configuration](https://docs.liquibase.com/reference-guide/parameters/changelog-file)
- [AWS deployment alternative](AWS_Deployment.md).
