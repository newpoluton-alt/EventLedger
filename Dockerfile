# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon dependencies

COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar && \
    cp "$(find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -print -quit)" /workspace/app.jar

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update && \
    apt-get install --yes --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --system --gid 10001 eventledger && \
    useradd --system --uid 10001 --gid eventledger --home-dir /app --shell /usr/sbin/nologin eventledger

WORKDIR /app
ADD --checksum=sha256:e5bb2084ccf45087bda1c9bffdea0eb15ee67f0b91646106e466714f9de3c7e3 \
    https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem /app/certs/global-bundle.pem
RUN chmod 0444 /app/certs/global-bundle.pem
COPY --from=build --chown=eventledger:eventledger /workspace/app.jar /app/app.jar

USER 10001:10001
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
