# Author: Carlos Scheidecker
FROM eclipse-temurin:25-jdk-noble@sha256:589ff4cc3f71aab462e7048a47a0d10edf57fbccde3fceea2281e610bf5880b4 AS build
RUN apt-get update && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml checkstyle.xml ./
RUN chmod +x mvnw
COPY src/ src/
# Real MySQL integration tests run on the host; do not mount Docker into the builder.
RUN ./mvnw --batch-mode --no-transfer-progress verify -DskipITs

FROM eclipse-temurin:25-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab
LABEL org.opencontainers.image.title="RestNotes" \
      org.opencontainers.image.authors="Carlos Scheidecker"
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/restnotes.jar /app/restnotes.jar
USER 10001:10001
ENV SERVER_PORT=8081 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8081
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
    CMD bash -ec 'exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT}; printf "GET /actuator/health/readiness HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n" >&3; IFS= read -r status <&3; [[ "$status" == *" 200 "* ]]'
ENTRYPOINT ["java", "-jar", "/app/restnotes.jar"]
