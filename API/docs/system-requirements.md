# System requirements and step-by-step setup
Author: Carlos Scheidecker

This guide runs the current RestNotes API from [Scheidecker/RestNotes on GitHub](https://github.com/Scheidecker/RestNotes). The recommended path uses Docker Compose to build the Java application and start MySQL together. No Angular client is included. This is a local development/demo setup, not a hardened production deployment.

## 1. Choose a computer and operating system
- Use a 64-bit desktop, laptop or development VM with hardware virtualization available when Docker needs a Linux VM. Ubuntu on x86-64 is the verified development baseline.
- A practical starting point is 4 logical CPU cores, 8 GiB RAM and 20 GiB free SSD space. Prefer 16 GiB RAM and more free disk for host development and the full integration suite. These are planning recommendations, not measured minimums or production capacity guarantees.
- Docker Desktop users should allocate enough CPU/memory to its Linux VM; roughly 6–8 GiB available to Docker is a reasonable starting budget for builds and tests on a suitably sized host. Testcontainers can start additional databases, and image/build caches grow over time.
- Linux: use a supported 64-bit distribution with Docker Engine and the Compose plugin. Ubuntu 24.04 LTS is a conservative clean-machine choice.
- Windows: use a currently supported Windows release with WSL 2 and Docker Desktop configured for Linux containers and WSL integration. Run this guide's Bash commands inside the integrated WSL distribution; keeping the checkout in its Linux filesystem avoids common path/permission/line-ending issues.
- macOS: use a currently supported macOS release and Docker Desktop. ARM64/Apple Silicon requires compatible pinned Java/MySQL images or an explicitly tested emulation setup; the project does not claim a validated cross-architecture matrix.

No GPU or dedicated database server is required for the local Compose workflow. Production sizing needs workload, retention, availability and security requirements, not a copy of these workstation recommendations.

## 2. Install the tools for your chosen path
For the recommended container-only path, install:
1. Git, to clone and update the repository. A GitHub account/credential is only needed if repository access requires it.
2. Docker Engine with the Compose plugin on Linux, or Docker Desktop on Windows/macOS. Use a maintained Compose plugin supporting `up --wait` (`docker compose`, not legacy `docker-compose`); enable Linux containers.
3. A shell/terminal. Bash is required for the full CURL walkthrough; use WSL Bash on Windows.
4. curl for health/API checks. Python 3 is required for the documented JSON/token/URI-encoding walkthroughs, but not merely to start the containers.
5. A browser if you want Swagger UI. It is optional for command-line use.

Follow the vendor installation instructions for your OS rather than installing an unrelated package named docker:
- [Docker Engine on Ubuntu](https://docs.docker.com/engine/install/ubuntu/)
- [Docker Desktop on Windows](https://docs.docker.com/desktop/setup/install/windows-install/)
- [Docker Desktop on macOS](https://docs.docker.com/desktop/setup/install/mac-install/)
- [Git installation](https://git-scm.com/downloads)
- [WSL installation on Windows](https://learn.microsoft.com/en-us/windows/wsl/install)
- [Python 3 downloads and installation guidance](https://www.python.org/downloads/)

Start Docker and confirm your user can access its daemon using an approved setup. Access to a privileged Docker daemon is highly privileged; do not solve permission errors by exposing its socket or making it world-writable. Check Docker Desktop licensing where applicable.

You do **not** need a host Java installation, a host Maven installation or a host MySQL installation for container-only startup. The Dockerfile builds with JDK 25, uses Maven Wrapper 3.9.16 and runs on a Java 25 JRE; Compose supplies MySQL 8.4.11. Node.js, npm, Angular CLI, Redis and H2 are not required.

The first build needs internet access to GitHub, container registries, Maven Central and the build image's Ubuntu package repositories. Corporate proxies, TLS interception and registry limits must be configured in the approved Docker/JVM tooling; do not disable certificate validation as a workaround.

## 3. Download the repository
From your chosen parent working directory:

```bash
git clone https://github.com/Scheidecker/RestNotes.git
cd RestNotes/API
```

If the code is already downloaded, enter its `API` directory instead of cloning over it. Commands below run from that directory, which contains `pom.xml`, `Dockerfile`, `docker-compose.yml`, `mvnw`, `.mvn/` and `docs/`. A source ZIP must include hidden files such as `.mvn/`; prefer Git to preserve wrapper permissions.

## 4. Check Docker and optional walkthrough tools

```bash
git --version
docker version
docker compose version
docker compose up --help
curl --version
python3 --version
```

`docker version` must show a reachable server, not only an installed client. Confirm `docker compose up --help` lists `--wait`. curl/Python are only needed for their corresponding walkthrough steps. Enable virtualization/WSL integration or start the Docker daemon if it is unavailable.

The default API port is 8081 on loopback only. MySQL is not published to the host; it uses port 3306 inside the Compose network. Ensure there is enough disk/memory and no host-port conflict before startup.

## 5. Review local configuration
No `.env` file is required for the default local demo. Review `.env.example`, `docker-compose.yml` and the [README configuration reference](../README.md#configuration). If you need overrides, create `.env` only when it does not already exist:

```bash
if [ ! -e .env ]; then
  cp .env.example .env
fi
docker compose config --quiet
```

The supplied defaults are public development credentials, not production secrets. Do not copy them into shared/cloud deployments. Never commit real credentials, signing keys or bearer/lock tokens. Avoid printing resolved Compose configuration when secrets are present; `config --quiet` validates without rendering them.

The base Compose file forwards only its listed environment variables. Merely adding arbitrary application variables to `.env` does not inject them into containers. In particular, database pool, migration and OpenAPI settings need explicit service-environment overrides if you want to alter them under Compose.

Keep the default dev profile for this demonstration. UAT/prod require non-demo credentials, no demo seeding, externally supplied matching RSA keys and a separately managed schema-migration process; changing only the profile name is not a production deployment.

## 6. Build and start the application

```bash
docker compose up --build --wait
docker compose ps
```

This downloads the pinned images/dependencies, builds and checks the API, initializes an empty MySQL volume, waits for database readiness, applies Liquibase migrations through 005, validates JPA mappings and starts the API. Local Compose explicitly enables the development fixtures. Allow several minutes on a first build; network speed and available resources affect startup.

The image builder runs unit tests and Checkstyle but skips Docker-backed integration tests. A Docker daemon is not mounted inside the builder. Use the optional host test path below for the full suite.

If port 8081 is occupied, select a different host port before starting:

```bash
SERVER_PORT=18081 docker compose up --build --wait
```

For that variant use port 18081 in subsequent URLs and set RN_BASE_URL accordingly in the CURL guide. The container still listens on 8081. Use the same port override on subsequent Compose operations that recreate the service.

## 7. Verify readiness and inspect the API

```bash
curl --fail-with-body http://localhost:8081/actuator/health
curl --fail-with-body http://localhost:8081/actuator/health/readiness
curl --fail-with-body http://localhost:8081/actuator/info
```

Expect health/readiness responses with status UP; info returns approved metadata identifying the note-locking increment. Open http://localhost:8081/swagger-ui.html for interactive API documentation or http://localhost:8081/v3/api-docs for OpenAPI JSON in dev/test.

Protected business routes require a bearer token. A 401 without a token does not indicate failed startup. Follow [registration/login](CURL_Guide.md#register-login-and-read-your-profile), then the team/notebook/note/tag/lock sections. Capture credentials/tokens as shown without printing them or enabling shell/curl tracing.

## 8. Use the development fixtures safely
The [schema reference](Database-Schema-Reference.md#required-development-seed-dataset) provides the local-only login credential and deterministic UUIDs. Fresh demo initialization creates:
- Engineering and Marketing teams.
- One distinct ADMIN user, notebook and authored note per team.
- One initial history snapshot and creation activity per sample note.

Emails use `@scheideckerantunes.com`. The teams initially have no cross-team memberships or sharing grants. New registrations receive no automatic memberships. Demo changesets do not reset records/passwords on restart; use your current credential if an initialized account has been changed.

API restart invalidates JWTs when dev uses ephemeral signing keys, so log in again. Database editing leases survive restart until their five-minute expiry; retain a separately captured valid lease token. Neither restarting the API nor logging in bypasses an active lease.

## 9. Stop, restart and preserve data

```bash
docker compose down
docker compose up --wait
```

Ordinary shutdown retains the named MySQL volume. To incorporate later source changes, use `docker compose up --build --wait` instead. Do not use volume-deletion flags for routine shutdown or troubleshooting.

MySQL initialization variables apply only to an empty volume. Editing a database password in `.env` does not rotate the password inside an existing database. Credential rotation, destructive resets and repair of failed migrations require a deliberate backed-up procedure, not deletion of data to make startup succeed.

For startup problems, inspect service state and local logs:

```bash
docker compose ps
docker compose logs --tail=100 api mysql
```

Review logs locally and redact sensitive information before sharing. Check daemon availability, memory/disk pressure, port conflicts, image architecture, proxy access and database credential consistency. Do not edit previously applied Liquibase changesets or disable TLS verification to suppress errors.

## 10. Optional: install host development and test dependencies
Only add these if compiling/testing outside the image:
1. Install a maintained **JDK 25**, not only a JRE; for example, use the [Eclipse Temurin installation guidance](https://adoptium.net/installation/), selecting version 25. Set JAVA_HOME and PATH so the wrapper uses it.
2. On Unix, install unzip plus curl or wget for Maven Wrapper bootstrap. Keep the checked-in checksum verification enabled. Native Windows wrapper bootstrap uses PowerShell/Expand-Archive, but this guide's examples assume Bash/WSL.
3. Keep Docker available for full integration tests. Testcontainers starts isolated MySQL instances; it does not need your Compose database or a host MySQL service.
4. Use the wrapper; a separate Maven installation is unnecessary.

```bash
java -version
./mvnw --version
./mvnw --batch-mode --no-transfer-progress test
./mvnw --batch-mode --no-transfer-progress clean verify
```

Expect Java 25 and Maven 3.9.16. `test` runs database-independent tests and style checks; `clean verify` additionally requires Docker, runs the real-MySQL integration suite and creates `target/restnotes.jar`. Testcontainers storage is disposable and separate from the Compose volume. These test commands do not start a persistent API.

To run the JVM directly rather than just test it, provision a reachable MySQL RestNotes database/non-root account and set DB_URL/DB_USERNAME/DB_PASSWORD as described in the [README](../README.md#run-locally). The default Compose configuration intentionally does not expose MySQL to a host JVM. Host-run dev loads fixtures only when DEMO_DATA_ENABLED=true.

See [features summary](features-summary.md), [API design](API.Design.md) and [implementation phases](implementation-phases.md) for implemented scope and remaining production work.
