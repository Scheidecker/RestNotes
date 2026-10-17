# AWS deployment
Author: Carlos Scheidecker
Provider guidance reviewed: 2026-10-17.

## Scope, safety and deployment model
This is a **runbook, not a completed cloud deployment**. Execute provisioning/push/IAM commands only with approval in the intended account/region. They create billable resources. Start in staging, set budgets and preserve backups; documentation creation does not authorize infrastructure creation or deletion.

Target: Amazon ECR → ECS Fargate Linux service behind an HTTPS Application Load Balancer → private RDS for MySQL. Secrets Manager supplies database credentials and application signing keys; CloudWatch handles logs. A separate one-off Fargate task runs Liquibase before service rollout.

Important compatibility details:
- The current application reads **PKCS#8 private/X.509 public PEM files**, not a JWT secret environment variable. ECS native secret injection supplies environment values, not those files. A nonessential initialization container must write a task-scoped shared volume before the non-root API starts.
- JDBC must verify the RDS server hostname and CA. This guide adds a public CA truststore to a release image; it does not disable TLS or assume the JRE already trusts RDS's private CA.
- The repository has no terminating migration-only application command. Prepare the separate Liquibase image below; do not run the server image as a supposedly finite job.
- No application source is changed by this guide. The infrastructure/task/image examples are templates to prepare, review and rehearse, not cloud-tested artifacts already included in the repository.

See [architecture](architecture.md), [configuration](../README.md#configuration), [schema](Database-Schema-Reference.md), [local requirements](system-requirements.md) and the [GCP alternative](GCP_Deployment.md).

## 1. Prepare the account, release workstation and budget
1. Choose an approved AWS account/region, data-residency policy and controlled domain for TLS (for example, a company-owned notes hostname). Configure billing alerts, ownership/cost tags and CloudTrail.
2. Install AWS CLI v2, Docker/buildx, Git, JDK 25/keytool, unzip, curl, Python 3, jq and OpenSSL. Use IAM Identity Center/SSO or workload-federated roles with MFA, not root-account credentials or committed access keys.
3. Have a provisioning administrator create the VPC/IAM/RDS/ALB resources. A release deployer needs only scoped ECR push, ECS deployment/run-task and iam:PassRole permissions for approved task roles. Separate that from database and secret administration.
4. Confirm identity and prepare a reviewed release:

```bash
export AWS_REGION='us-east-1'
export AWS_DEFAULT_REGION="$AWS_REGION"
aws sts get-caller-identity
export RN_ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
export RN_REGISTRY="${RN_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
export RN_CLUSTER='restnotes'
export RN_SERVICE='restnotes-api'
./mvnw --batch-mode --no-transfer-progress clean verify
export RN_RELEASE="$(git rev-parse --short=12 HEAD)"
export RN_RELEASE_DIR="$(mktemp -d)"
```

Require a clean/reviewed checkout. Store release metadata (commit, image digests, task-definition revisions, database version, configuration and secret version IDs) outside Git unless it is deliberately non-secret infrastructure configuration. Do not print environment contents or enable shell tracing.

## 2. Create VPC networking before workloads
Using VPC Console → Create VPC → VPC and more, or reviewed infrastructure-as-code:
1. Create a dedicated, non-overlapping CIDR and enable DNS resolution/hostnames.
2. Use at least two Availability Zones: public subnets for an internet-facing ALB and private subnets for Fargate. Create a private RDS subnet group spanning at least two AZs; dedicated database subnets are preferable.
3. Public subnet routes go to an Internet Gateway. Private tasks have **no public IP**.
4. Give private tasks outbound access through an approved NAT design, or use interface endpoints for ECR API, ECR Docker, Secrets Manager and CloudWatch Logs plus an S3 gateway endpoint for ECR image layers. Enable private DNS, endpoint policies and TCP 443 access from task security groups. Fargate itself does not require the ECS interface endpoints merely to run tasks.
5. If using NAT, choose the availability/cross-AZ trade-off deliberately; NAT per AZ avoids one common dependency but costs more. Endpoints also have standing cost. Mirror every runtime image into ECR for endpoint-only deployments.

Create distinct security groups:
- **ALB:** inbound 443 initially only from approved operator CIDRs/VPN; optional 80 only for HTTPS redirection. Outbound to the API security group on 8081.
- **API tasks:** inbound 8081 only from ALB. Outbound 3306 to RDS and required HTTPS/DNS through approved endpoints/NAT.
- **Migration/admin runner:** no public inbound; outbound 3306 to RDS and required image/secret/log endpoints.
- **RDS:** inbound 3306 only from the API and migration/admin runner groups. No public exposure and no 0.0.0.0/0 database rule.

Check routes, network ACLs, DNS and endpoint SGs; IAM permission alone does not establish connectivity. Use approved SSM/VPN/private runners for DBA access, not an open SSH/MySQL bastion.

## 3. Create RDS MySQL and database identities
Check region availability before provisioning:

```bash
aws rds describe-db-engine-versions --engine mysql --engine-version 8.4.11 \
  --query 'DBEngineVersions[].{Version:EngineVersion,Status:Status,Family:DBParameterGroupFamily}' \
  --output table
```

An empty result blocks this exact-version path. Do not silently switch to MySQL 8.0, Aurora, MariaDB or a different patch and call it validated. Record the selected managed version and rehearse the application/migrations against matching staging.

In RDS Console → Create database:
1. Select **RDS for MySQL 8.4.11** if available, not Aurora. Choose production/staging settings appropriate to your budget; prefer Multi-AZ for production.
2. Select the private subnet group and RDS SG, **Public access: No**, storage encryption/KMS policy, deletion protection, automated backups/PITR retention and maintenance windows.
3. Manage the instance-admin credential securely (RDS-managed Secrets Manager is suitable). Do not inject that admin credential into either application or migration task.
4. Create a parameter group for the chosen 8.4 family, with `character_set_server=utf8mb4`, `collation_server=utf8mb4_0900_ai_ci` and `require_secure_transport=1` where supported. Apply/reboot as required by each parameter.
5. Select a supported RDS CA and record its rotation/expiry policy. Record the actual RDS DNS endpoint, not an IP or custom CNAME.
6. Create database `RestNotes` and separate non-root `restnotes_app` and `restnotes_migrator` users through approved private DBA tooling. Generate independent passwords, store them directly in Secrets Manager, and remove broad/default grants.

The desired grants, after removing broader privileges, are:

```sql
-- Author: Carlos Scheidecker
GRANT SELECT, INSERT, UPDATE, DELETE ON `RestNotes`.* TO 'restnotes_app'@'%';
GRANT ALL PRIVILEGES ON `RestNotes`.* TO 'restnotes_migrator'@'%';
SHOW GRANTS FOR 'restnotes_app'@'%';
SHOW GRANTS FOR 'restnotes_migrator'@'%';
```

These do not revoke previous permissions. Neither workload needs global administration or permission to create other databases. Use modern caching_sha2_password-compatible clients/drivers; do not weaken authentication to make an old client work.

Store the endpoint/instance identifier as non-secret release settings:

```bash
export RN_DB_INSTANCE='REPLACE_DB_INSTANCE_IDENTIFIER'
export RN_RDS_ENDPOINT="$(aws rds describe-db-instances \
  --db-instance-identifier "$RN_DB_INSTANCE" \
  --query 'DBInstances[0].Endpoint.Address' --output text)"
```

## 4. Create Secrets Manager entries and task roles
Create raw-string secrets for the app DB password, migration DB password and each JWT PEM. **Raw plaintext PEM**, not a JSON object containing it, is expected by the init-container example. For new keys, generate a matching pair outside the repository:

```bash
export RN_KEY_DIR="$(mktemp -d)"
chmod 700 "$RN_KEY_DIR"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 \
  -out "$RN_KEY_DIR/private.pem"
chmod 600 "$RN_KEY_DIR/private.pem"
openssl pkey -in "$RN_KEY_DIR/private.pem" -pubout -out "$RN_KEY_DIR/public.pem"
aws secretsmanager create-secret --name restnotes/jwt-private \
  --secret-string "file://$RN_KEY_DIR/private.pem" --query ARN --output text
aws secretsmanager create-secret --name restnotes/jwt-public \
  --secret-string "file://$RN_KEY_DIR/public.pem" --query ARN --output text
```

Create `restnotes/db-password` and `restnotes/migration-password` using the approved secret-entry workflow with the already-created database passwords. Do not use `--secret-string` with a literal password, print get-secret-value output or bake credentials into an image. Secure/remove temporary private-key material under your organization's procedure.

Record complete secret ARNs and immutable version IDs. ECS raw-string version pinning uses `SECRET_ARN:::VERSION_ID`. The API, init container and overlapping tasks must use the same matching key versions. A later Secrets Manager update does not refresh running task environment values or keys already loaded by Java.

Create IAM roles trusted by `ecs-tasks.amazonaws.com`:
- **Service task execution role:** ECR pull and CloudWatch log-stream permissions, plus secretsmanager:GetSecretValue on exactly the application DB password and two key secrets. Add kms:Decrypt only for the specific customer-managed KMS keys used. The managed AmazonECSTaskExecutionRolePolicy is a starting point, not permission to read every secret.
- **Migration task execution role:** image/log permissions plus only the migration-password secret/KMS permission; no signing-key access.
- **Application task role:** no AWS API permissions unless the application itself needs them. Native secret injection is performed using the execution role, not this role.

Pre-create `/ecs/restnotes` and `/ecs/restnotes-migrate` CloudWatch log groups with approved retention/encryption. Avoid broad resource permissions or granting Secrets Manager Administrator to runtime roles.

## 5. Prepare public RDS trust material
Download the current official RDS bundle over verified HTTPS into RN_RELEASE_DIR; it contains public certificates, not a private key:

```bash
curl --fail --show-error --location \
  https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem \
  --output "$RN_RELEASE_DIR/rds-global-bundle.pem"
mkdir -p "$RN_RELEASE_DIR/rds-roots"
python3 - <<'PY'
import os,pathlib,re,subprocess
root=pathlib.Path(os.environ["RN_RELEASE_DIR"])
certs=re.findall(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----",
                 (root/"rds-global-bundle.pem").read_text(),re.S)
assert certs,"Empty RDS certificate bundle"
for i,cert in enumerate(certs):
    path=root/"rds-roots"/f"root-{i}.pem"
    path.write_text(cert+"\n")
    result=subprocess.check_output(["openssl","x509","-in",str(path),"-noout",
                                    "-subject","-issuer","-nameopt","RFC2253"],text=True)
    subject,issuer=result.splitlines()
    assert subject.split("=",1)[1]==issuer.split("=",1)[1],"Review non-root certificate before import"
PY
export RN_TRUST_PASSWORD='public-ca-store'
for certificate in "$RN_RELEASE_DIR"/rds-roots/*.pem; do
  keytool -importcert -noprompt -storetype PKCS12 \
    -keystore "$RN_RELEASE_DIR/rds-trust.p12" -storepass:env RN_TRUST_PASSWORD \
    -alias "$(basename "$certificate" .pem)" -file "$certificate"
done
```

Review the bundle's provenance/CA fingerprints against AWS documentation; do not import leaf/intermediate certificates as permanent trust anchors. The `public-ca-store` password is intentionally public: this truststore holds only public CA certificates, never private keys or database credentials. Its purpose is integrity/format access, not secret storage. Do not reuse this convention for a private-key keystore.

Both runtime and migration images will contain this public truststore at `/opt/restnotes/rds-trust.p12`. Their non-secret JDBC URL is:

```bash
export RN_DB_URL="jdbc:mysql://${RN_RDS_ENDPOINT}:3306/RestNotes?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/opt/restnotes/rds-trust.p12&trustCertificateKeyStoreType=PKCS12&trustCertificateKeyStorePassword=public-ca-store"
```

Username and **database** password stay separate. Never set trustServerCertificate, sslMode=DISABLED/PREFERRED, or disable hostname verification to suppress certificate errors. Test CA rotation before rollout and rebuild the trust image when the trusted CA set changes.

## 6. Build and publish immutable images

```bash
aws ecr create-repository --repository-name restnotes-api --image-tag-mutability IMMUTABLE
aws ecr create-repository --repository-name restnotes-migrate --image-tag-mutability IMMUTABLE
export RN_ECR_LOGIN_PASSWORD="$(aws ecr get-login-password)"
printf '%s' "$RN_ECR_LOGIN_PASSWORD" |
  docker login --username AWS --password-stdin "$RN_REGISTRY"
unset RN_ECR_LOGIN_PASSWORD
docker buildx build --platform linux/amd64 --push \
  --tag "$RN_REGISTRY/restnotes-api:base-$RN_RELEASE" .
export RN_BASE_DIGEST="$(aws ecr describe-images --repository-name restnotes-api \
  --image-ids imageTag="base-$RN_RELEASE" --query 'imageDetails[0].imageDigest' --output text)"
export RN_BASE_IMAGE="$RN_REGISTRY/restnotes-api@$RN_BASE_DIGEST"
./mvnw --batch-mode --no-transfer-progress dependency:copy-dependencies \
  -DincludeArtifactIds=mysql-connector-j -DoutputDirectory="$RN_RELEASE_DIR/drivers"
mkdir -p "$RN_RELEASE_DIR/changelog"
cp -R src/main/resources/db/changelog/. "$RN_RELEASE_DIR/changelog/"
```

If repositories already exist, inspect their policy instead of recreating/deleting them. Enable approved image scanning and retain earlier release digests. Immutable tags cannot be overwritten: use a new release identifier if the contents change.

Save these Dockerfiles inside RN_RELEASE_DIR, which must contain no secret key/password files.

`Dockerfile.aws` adds only public RDS trust to the application's existing non-root image:

```dockerfile
# Author: Carlos Scheidecker
ARG APP_BASE_IMAGE
FROM ${APP_BASE_IMAGE}
COPY --chown=10001:10001 --chmod=0444 rds-trust.p12 /opt/restnotes/rds-trust.p12
```

`Dockerfile.migrate` uses a reviewed Liquibase 5.0.4 image **digest**, the Boot-resolved MySQL JDBC driver and unchanged schema files:

```dockerfile
# Author: Carlos Scheidecker
ARG LIQUIBASE_IMAGE
FROM ${LIQUIBASE_IMAGE}
COPY --chmod=0444 drivers/*.jar /liquibase/lib/
COPY --chmod=0555 changelog/ /liquibase/changelog/db/changelog/
COPY --chmod=0444 rds-trust.p12 /opt/restnotes/rds-trust.p12
```

```bash
export RN_LIQUIBASE_IMAGE='REPLACE_LIQUIBASE_5_0_4_IMAGE_AT_SHA256'
docker buildx build --platform linux/amd64 --push \
  --build-arg APP_BASE_IMAGE="$RN_BASE_IMAGE" \
  -f "$RN_RELEASE_DIR/Dockerfile.aws" \
  --tag "$RN_REGISTRY/restnotes-api:$RN_RELEASE" "$RN_RELEASE_DIR"
docker buildx build --platform linux/amd64 --push \
  --build-arg LIQUIBASE_IMAGE="$RN_LIQUIBASE_IMAGE" \
  -f "$RN_RELEASE_DIR/Dockerfile.migrate" \
  --tag "$RN_REGISTRY/restnotes-migrate:$RN_RELEASE" "$RN_RELEASE_DIR"
```

Record the final api and migration `repository@sha256:...` values using describe-images, scan them and test migrations on a disposable staging schema. Liquibase Community 5 may omit JDBC drivers by default; do not assume a stock CLI image can connect to MySQL. Preserve changelog identity `db/changelog/schema.yml` to avoid reapplying old changes under a different filename.

## 7. Run the one-off migration task
Create an ECS cluster with Fargate capacity. Register a separate migration task definition in Console:
1. Linux/X86_64, awsvpc, Fargate platform 1.4.0 or later, initially 0.5–1 vCPU with an allowed memory pairing (for example 1 vCPU/2 GiB).
2. Use the migration execution role and a minimal/no-permission task role.
3. Add one essential container using the **migration image digest**.
4. Keep the Liquibase image entrypoint; set its command array to `["--search-path=/liquibase/changelog","--changelog-file=db/changelog/schema.yml","update"]`.
5. Set LIQUIBASE_COMMAND_URL to RN_DB_URL, LIQUIBASE_COMMAND_USERNAME=restnotes_migrator and LIQUIBASE_ANALYTICS_ENABLED=false. Inject LIQUIBASE_COMMAND_PASSWORD from the pinned migration secret version.
6. Configure awslogs to `/ecs/restnotes-migrate`; no ports/load balancer/JWT keys/prod profile are needed by this CLI.
7. Run **one standalone task**, not an ECS service, in the private migration subnets/SG, with no public IP. Wait for STOPPED and inspect exitCode=0 and task failures/logs before rollout.

Example status inspection, after saving the actual task ARN:

```bash
export RN_MIGRATION_TASK_ARN='REPLACE_TASK_ARN'
aws ecs wait tasks-stopped --cluster "$RN_CLUSTER" --tasks "$RN_MIGRATION_TASK_ARN"
aws ecs describe-tasks --cluster "$RN_CLUSTER" --tasks "$RN_MIGRATION_TASK_ARN" \
  --query '{Failures:failures,Tasks:tasks[].{Reason:stoppedReason,Containers:containers[].{Name:name,Exit:exitCode,Reason:reason}}}'
```

A completed waiter does not prove successful migration: inspect the container exit code. Confirm DATABASECHANGELOG contains 001, 002, 004 and 005, no demo 003, expected schema constraints and no demo users/teams. A fresh prod database starts empty.

On failure, stop deployment and inspect schema plus migration records. MySQL DDL can commit partially; do not clear checksums/locks or blindly retry a partially applied SQL file. Never use the long-running application image with migrations enabled as a replacement for this finite task.

## 8. Create ALB, TLS certificate and DNS
1. In ACM, request/validate a public certificate for your controlled API hostname **in the ALB's region**.
2. Create an internet-facing Application Load Balancer in the public subnets across both workload AZs, with the ALB SG and approved access/log settings.
3. Create a dedicated target group: **target type ip**, protocol HTTP, port 8081, selected VPC.
4. Set health path `/actuator/health/readiness`, success matcher 200 and reviewed thresholds/timeouts. Choose deregistration delay and ECS grace period compatible with application graceful shutdown.
5. Add HTTPS listener 443 using the validated ACM certificate and a maintained TLS policy; forward to this target group. Optionally redirect HTTP 80 to HTTPS.
6. Create Route 53 Alias (or appropriate DNS provider record) for your hostname to the ALB. Do not expect the raw ALB DNS name to match your custom-domain certificate.
7. Keep operator/VPN ingress restrictions through bootstrap. Before public exposure add AWS WAF/rate controls and a reviewed authentication-abuse policy; application JWT support is not edge protection.

TLS terminates at the ALB; this example uses private SG-restricted HTTP to tasks. If your classification requires encryption on that hop, use a deliberately configured HTTPS target/application or service-mesh design and validate it; this guide does not claim end-to-end TLS on an HTTP target group.

## 9. Register the runtime task with secret-file initialization
Save the following as `$RN_RELEASE_DIR/task.json`. Replace all placeholders and the JDBC endpoint. Pin raw-string secret references with complete ARNs and version IDs. JSON intentionally contains references, not secret values:

```json
{
  "family": "restnotes-api",
  "requiresCompatibilities": ["FARGATE"],
  "networkMode": "awsvpc",
  "runtimePlatform": {"operatingSystemFamily": "LINUX", "cpuArchitecture": "X86_64"},
  "cpu": "1024",
  "memory": "2048",
  "executionRoleArn": "REPLACE_SERVICE_EXECUTION_ROLE_ARN",
  "taskRoleArn": "REPLACE_MINIMAL_TASK_ROLE_ARN",
  "volumes": [{"name": "jwt-files"}],
  "containerDefinitions": [
    {
      "name": "key-init",
      "image": "REPLACE_API_IMAGE_AT_SHA256",
      "essential": false,
      "user": "0",
      "entryPoint": ["/bin/bash", "-ec"],
      "command": [
        "umask 077; test -n \"$JWT_PRIVATE_PEM\"; test -n \"$JWT_PUBLIC_PEM\"; mkdir -p /run/restnotes-keys; printf '%s\\n' \"$JWT_PRIVATE_PEM\" > /run/restnotes-keys/private.pem; printf '%s\\n' \"$JWT_PUBLIC_PEM\" > /run/restnotes-keys/public.pem; chmod 0700 /run/restnotes-keys; chmod 0400 /run/restnotes-keys/*.pem; chown -R 10001:10001 /run/restnotes-keys"
      ],
      "secrets": [
        {"name": "JWT_PRIVATE_PEM", "valueFrom": "REPLACE_PRIVATE_SECRET_ARN:::REPLACE_VERSION_ID"},
        {"name": "JWT_PUBLIC_PEM", "valueFrom": "REPLACE_PUBLIC_SECRET_ARN:::REPLACE_VERSION_ID"}
      ],
      "mountPoints": [{"sourceVolume": "jwt-files", "containerPath": "/run/restnotes-keys", "readOnly": false}],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {"awslogs-group": "/ecs/restnotes", "awslogs-region": "REPLACE_REGION", "awslogs-stream-prefix": "init"}
      }
    },
    {
      "name": "api",
      "image": "REPLACE_API_IMAGE_AT_SHA256",
      "essential": true,
      "user": "10001:10001",
      "memory": 1024,
      "stopTimeout": 30,
      "linuxParameters": {"initProcessEnabled": true, "capabilities": {"drop": ["ALL"]}},
      "dependsOn": [{"containerName": "key-init", "condition": "SUCCESS"}],
      "portMappings": [{"containerPort": 8081, "protocol": "tcp"}],
      "mountPoints": [{"sourceVolume": "jwt-files", "containerPath": "/run/restnotes-keys", "readOnly": true}],
      "environment": [
        {"name": "SERVER_PORT", "value": "8081"},
        {"name": "SPRING_PROFILES_ACTIVE", "value": "prod"},
        {"name": "DEMO_DATA_ENABLED", "value": "false"},
        {"name": "MIGRATIONS_ENABLED", "value": "false"},
        {"name": "REGISTRATION_ENABLED", "value": "false"},
        {"name": "OPENAPI_ENABLED", "value": "false"},
        {"name": "DB_USERNAME", "value": "restnotes_app"},
        {"name": "DB_POOL_SIZE", "value": "5"},
        {"name": "DB_URL", "value": "jdbc:mysql://REPLACE_RDS_ENDPOINT:3306/RestNotes?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/opt/restnotes/rds-trust.p12&trustCertificateKeyStoreType=PKCS12&trustCertificateKeyStorePassword=public-ca-store"},
        {"name": "JWT_ISSUER", "value": "RestNotes"},
        {"name": "JWT_AUDIENCE", "value": "RestNotes-api"},
        {"name": "JWT_TTL_SECONDS", "value": "900"},
        {"name": "JWT_PRIVATE_KEY_LOCATION", "value": "file:/run/restnotes-keys/private.pem"},
        {"name": "JWT_PUBLIC_KEY_LOCATION", "value": "file:/run/restnotes-keys/public.pem"}
      ],
      "secrets": [{"name": "DB_PASSWORD", "valueFrom": "REPLACE_APP_PASSWORD_SECRET_ARN:::REPLACE_VERSION_ID"}],
      "healthCheck": {
        "command": ["CMD", "bash", "-ec", "exec 3<>/dev/tcp/127.0.0.1/8081; printf 'GET /actuator/health/liveness HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3; IFS= read -r status <&3; [[ \"$status\" == *\" 200 \"* ]]"],
        "interval": 30,
        "timeout": 5,
        "retries": 3,
        "startPeriod": 60
      },
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {"awslogs-group": "/ecs/restnotes", "awslogs-region": "REPLACE_REGION", "awslogs-stream-prefix": "api"}
      }
    }
  ]
}
```

The JSON has no comment field for author metadata; this enclosing document is attributed to Carlos Scheidecker. If you save supporting scripts/Dockerfiles, retain their author comments.

The init process runs as root **inside its container only** so it can set UID 10001 ownership on the ephemeral volume; the API stays non-root and mounts the volume read-only. Fargate encrypts ephemeral task storage; files disappear when the task ends. Do not publish the volume externally or give it to unrelated containers. `SUCCESS` dependency and an essential API prevent starting against missing keys; init must be nonessential because it exits normally.

Do not enable tracing/debug dumps or print key env values in init logs. ECS/metadata/debugging access remains highly privileged. Avoid a read-only API root filesystem until its required temporary directories have explicit writable mounts and are tested; the task volume itself is already read-only for the API.

## 10. Create the service and deploy

```bash
export RN_TASK_DEFINITION_ARN="$(aws ecs register-task-definition \
  --cli-input-json "file://$RN_RELEASE_DIR/task.json" \
  --query 'taskDefinition.taskDefinitionArn' --output text)"
```

In ECS Console → Cluster → Create service:
1. Choose Fargate, the registered task revision and platform 1.4.0 or later.
2. Select private subnets/API SG, **public IP disabled**, and the ALB target group mapping to container `api:8081`. Let ECS register targets; do not register ephemeral task IPs manually.
3. Start with two tasks for production availability (one can be used in staging). Enable deployment failure detection/circuit-breaker rollback and an initial health-check grace period around 120 seconds, tuned after rehearsal.
4. Set minimum healthy 100% and maximum 200% only if the database/network budgets support the temporary doubled tasks. Configure autoscaling min/max and alarms deliberately.
5. Wait for service stability and healthy ALB targets:

```bash
aws ecs wait services-stable --cluster "$RN_CLUSTER" --services "$RN_SERVICE"
aws ecs describe-services --cluster "$RN_CLUSTER" --services "$RN_SERVICE" \
  --query 'services[].{Running:runningCount,Desired:desiredCount,Deployments:deployments,Events:events[:5]}'
```

Budget `peak tasks across old/new deployments × DB_POOL_SIZE + migration/admin headroom` below max_connections; a max task setting is not permission to exhaust RDS. A runtime pool of 5 is a starting example, not benchmarked production sizing. Test OOM behavior, lock contention, AZ failures and startup before raising concurrency.

Later releases migrate once first, register a new task definition with new image/secret-version references, then update the service. Force-new-deployment alone does not change a pinned secret version. Service stability does not replace application smoke tests.

## 11. Bootstrap users and verify application security
Set RN_BASE_URL to your validated HTTPS hostname:

```bash
export RN_BASE_URL='https://REPLACE_CONTROLLED_API_HOSTNAME'
curl --fail-with-body "$RN_BASE_URL/actuator/health/readiness"
curl --silent --show-error --output /dev/null --write-out '%{http_code}\n' \
  "$RN_BASE_URL/v3/api-docs"
```

Expect UP and disabled-docs application 401/403, not Swagger. Do not use curl --insecure or weaken JDBC verification to make checks succeed. Confirm private RDS sessions negotiate TLS and the API cannot create/alter tables with runtime credentials.

Fresh schema-only deployment has no users. Keep ALB ingress restricted to approved operator CIDRs/VPN, temporarily deploy REGISTRATION_ENABLED=true, and follow the [CURL guide](CURL_Guide.md) to register real users, log in, create a team and administer access. Restore false immediately afterward and verify registration is denied. Team creation makes the caller its team ADMIN; no global-admin account exists. Do not enable demo data or manually insert a guessed hash.

Test current account/profile, team isolation, owner/shared VIEWER/EDITOR caps, notebook/notes/tags, stale 412/missing 428, locked 423/old-token 409 and archive hiding. Confirm JWTs work across tasks using the same key pair and that a task replacement retains database leases/content. Capture tokens via environment/header streams as documented, never curl command-line literals or CI logs.

Before broad public access, complete the WAF/edge-throttling, credential recovery/abuse monitoring and security review gates. The current app does not implement MFA, email verification or a complete production identity lifecycle. Configure trusted CORS/CSRF/session policy only when the future browser UI design is settled.

## 12. Observe, back up and rotate
- Alert on ALB 5xx/latency/unhealthy targets, ECS restarts/OOM/deployment failures, RDS connections/storage/locks/replication and failed backups. Do not tie process liveness to database availability and create a restart storm; ALB readiness and ECS liveness have different purposes.
- Use CloudWatch/CloudTrail with retention, access controls and log redaction. Do not publish note bodies, credentials, environment dumps or key initialization output.
- Test snapshot/PITR restore into a new instance with the required subnet/SG/parameter/CA/user settings. Rehearse endpoint/secret changes and smoke tests before cutover; set RPO/RTO and a write-freeze/reconciliation procedure.
- Rotate DB credentials in RDS and Secrets Manager together, change pinned versions and replace tasks. Keys are loaded once at startup; rotate both matching PEM versions and all replicas coherently. There is no overlapping-key verification support, so signing-key replacement invalidates old JWTs and requires re-login.
- Review RDS CA rotation, truststore updates, maintenance/engine upgrades and dependency/container patches in staging. Managed patching does not replace application compatibility testing.

## 13. Application rollback versus database recovery
Keep the last healthy task-definition ARN/image/configuration/secret versions. If the migrated schema remains compatible, update back to that task revision:

```bash
export RN_PREVIOUS_TASK_DEFINITION='REPLACE_HEALTHY_TASK_DEFINITION_ARN'
aws ecs update-service --cluster "$RN_CLUSTER" --service "$RN_SERVICE" \
  --task-definition "$RN_PREVIOUS_TASK_DEFINITION"
aws ecs wait services-stable --cluster "$RN_CLUSTER" --services "$RN_SERVICE"
```

An ECS rollback/circuit breaker does not undo SQL or restore secret/key compatibility. Current SQL-file migrations have no general automatic rollback guarantee; MySQL DDL may be partially committed. Prefer additive expand/contract migrations and reviewed forward repairs. For destructive failure, restore to a new RDS instance and deliberately cut over after verifying data and accounting for writes since the restore point. Restoring a database also restores historical operational rows such as leases; reconcile their expiry/state before reopening writes.

Never treat Liquibase checksum clearing, deleting migration-lock rows or manually dropping tables as routine rollback steps.

## 14. Cost control and deliberate cleanup
RDS/Multi-AZ/storage/backups, ALB, NAT gateways or interface endpoints, Fargate tasks, public IPv4 addresses, CloudWatch, ECR and Secrets Manager all contribute costs. Scaling ECS to zero does not stop RDS, ALB or network standing charges. Budgets warn; they do not guarantee a spend cap.

For a disposable staging environment, obtain approval and preserve audit records/final tested backups first. Then drain/delete the dedicated ECS service, stop standalone tasks, remove dedicated listeners/target groups/ALB, and review RDS deletion protection/final snapshots before database deletion. Remove only unused dedicated images, secrets, IAM roles, endpoints/NAT and VPC resources after checking dependencies. Retained snapshots/logs/secrets can still cost money; deleting them may violate retention/recovery needs.

No broad account/VPC/database deletion script is provided. Verify remaining billable resources and required backup retention explicitly. A single VM with Compose can be a cheaper demo alternative, but it is not equivalent to the HA/networking/operational guarantees of this design.

## References and validation boundary
No AWS provisioning or live deployment was executed by this documentation change. CLI/JSON examples require account-specific validation, placeholder replacement and a staging rehearsal. This guide is not an automated CloudFormation/Terraform stack.

- [Fargate task definition parameters](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definition_parameters.html)
- [ECS secrets and shared-volume sidecars](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/specifying-sensitive-data.html)
- [Secret version/reference syntax and refresh behavior](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/secrets-envvar-secrets-manager.html)
- [ECS with an Application Load Balancer](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/alb.html)
- [Fargate VPC endpoints and dependencies](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/vpc-endpoints.html)
- [RDS MySQL versions](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/MySQL.Concepts.VersionMgmt.html)
- [RDS CA bundles and rotation](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.SSL.html)
- [Liquibase changelog configuration](https://docs.liquibase.com/reference-guide/parameters/changelog-file).
