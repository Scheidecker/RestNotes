# Quick start — Phase 1
Author: Carlos Scheidecker

From the API directory, run `docker compose up --build --wait`. Alternatively install JDK 25 and run `./mvnw spring-boot:run`. No database credentials are required for the foundation.

Open http://localhost:8081/actuator/health (expect UP) and http://localhost:8081/swagger-ui.html (expect the RestNotes definition without business operations).

Run `./mvnw verify` to execute current tests/style checks and package the API. Stop Compose with `docker compose down`, without deleting volumes.

Notes, teams, demo users, login and database wiring are upcoming phases. The optional MySQL scaffold is not needed for this quick start. Do not expect the future seeded accounts to authenticate yet.

See [README](../README.md), [API design](API.Design.md) and [CURL guide](CURL_Guide.md). Review and manually commit this increment before requesting Phase 2.
