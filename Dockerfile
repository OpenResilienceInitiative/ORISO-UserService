FROM eclipse-temurin:21-jre@sha256:273396ed5998598ed1091e8d72711c2d36980a0e65103859c55a4e977a41ffd3
# Pebble is Canonical's service manager shipped with the Ubuntu base image.
# It is unused by this single-process Java runtime and carries fixable
# HIGH Go stdlib/x-net CVEs, so it is removed from the final filesystem.
RUN rm -f /usr/bin/pebble
# Upgrade the pinned base's OpenSSL and curl packages before scanning.
# curl/libcurl4t64 need the Ubuntu fix for CVE-2026-92392, libbrotli1 the one for
# CVE-2026-111497.
RUN apt-get update \
    && apt-get install -y --no-install-recommends --only-upgrade libssl3t64 openssl openssl-provider-legacy curl libcurl4t64 libbrotli1 \
    && rm -rf /var/lib/apt/lists/*
VOLUME ["/tmp","/log"]
EXPOSE 8082
ARG JAR_FILE
ENV JAVA_UPPER_VERSION=eclipse-temurin:21-jre@sha256:273396ed5998598ed1091e8d72711c2d36980a0e65103859c55a4e977a41ffd3
COPY ./target/UserService.jar app.jar
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-XX:MaxRAMPercentage=75","-jar","/app.jar"]
