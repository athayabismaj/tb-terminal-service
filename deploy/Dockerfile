FROM gradle:9.1.0-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle buildFatJar --no-daemon --no-configuration-cache

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
    && curl --fail --silent --show-error https://www.postgresql.org/media/keys/ACCC4CF8.asc --output /tmp/postgresql.asc \
    && gpg --dearmor --output /usr/share/keyrings/postgresql.gpg /tmp/postgresql.asc \
    && echo "deb [signed-by=/usr/share/keyrings/postgresql.gpg] https://apt.postgresql.org/pub/repos/apt jammy-pgdg main" > /etc/apt/sources.list.d/postgresql.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends postgresql-client-16 \
    && rm -f /tmp/postgresql.asc \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home --home-dir /opt/tb-terminal tbterminal
WORKDIR /opt/tb-terminal
COPY --from=build /workspace/build/libs/tb-terminal-service-1.0.0-all.jar app.jar
RUN mkdir -p /var/lib/tb-terminal/backups && chown -R tbterminal:tbterminal /opt/tb-terminal /var/lib/tb-terminal
USER 10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 CMD curl --fail --silent http://127.0.0.1:8080/ready || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/tb-terminal/app.jar"]
