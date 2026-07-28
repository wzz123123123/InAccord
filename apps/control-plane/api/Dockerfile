# Generated only by scripts/ci/render-runtime-dockerfiles.mjs.
ARG BUILD_IMAGE=docker.io/library/eclipse-temurin@sha256:15ec53e5373fbede42fb2c1ec5b7e3ccbe1b0c9ad232ed67a16ed1b8b47070b6
FROM ${BUILD_IMAGE} AS build
ARG GRADLE_CACHE_SHA256
ARG SOURCE_DATE_EPOCH
ENV GRADLE_USER_HOME=/opt/verified-gradle-cache/cache
ENV SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH}
WORKDIR /opt/verified-gradle-cache
COPY build/ci/verified-gradle-cache/${GRADLE_CACHE_SHA256}/ ./
RUN --network=none ["sha256sum","--check","manifest.sha256"]
WORKDIR /workspace
COPY . .
RUN --network=none ["./gradlew","--offline","--no-daemon","--dependency-verification=strict",":apps:control-plane:api:bootJar"]
ARG RUNTIME_IMAGE=docker.io/library/eclipse-temurin@sha256:c978ac6dd6aa90ec21935eb6c848f50ebf9b783160eefdd6accd8d2fd9ebeef1
FROM ${RUNTIME_IMAGE}
LABEL org.opencontainers.image.title="control-plane-api"
WORKDIR /opt/accord
COPY --from=build --chown=10001:10001 /workspace/apps/control-plane/api/build/libs/api-0.1.0-SNAPSHOT.jar /opt/accord/app.jar
USER 10001:10001
ENTRYPOINT ["/usr/bin/java","-jar","/opt/accord/app.jar"]
