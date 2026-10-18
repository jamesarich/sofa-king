# Runs the installed distribution built on the host: ./gradlew :server:installDist
# The build context only admits server/build/install/server (see .dockerignore).

# Splits the distribution so the third-party jars, which change rarely, get a layer of their own.
# It only moves platform-independent files, so it runs natively on the build host.
FROM --platform=$BUILDPLATFORM busybox:1.38@sha256:fd7dc98638c8e305f4dc34e979f1c0fdfdcaeb0fbf8fcff77ae834b6da3d7e6e AS split
COPY server/build/install/server /dist
RUN mkdir -p /app/lib \
    && mv /dist/lib/server-*.jar /dist/lib/contract-jvm-*.jar /app/lib/ \
    && mv /dist/bin /app/bin \
    && rm /app/bin/*.bat

# Pinned by digest so a rebuild gets the same base; the tag names the JRE it holds.
FROM eclipse-temurin:21.0.12.1_1-jre-noble@sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c

# tini reaps the FFmpeg processes the server starts for probes and remuxing, under any container runtime.
# sqlite3 takes online database backups from inside the container. comskip (GPL, run as its own program) finds commercial breaks in recordings.
# The VA-API drivers (Mesa for AMD; Intel's media driver and Quick Sync runtime on amd64) serve SOFA_HWACCEL.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg comskip curl ca-certificates tini sqlite3 mesa-va-drivers \
        $(if [ "$(dpkg --print-architecture)" = amd64 ]; then echo intel-media-va-driver libmfx-gen1.2; fi) \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 sofa \
    && useradd --system --uid 10001 --gid sofa --home-dir /config --shell /usr/sbin/nologin sofa \
    && mkdir -p /config /recordings \
    && chown sofa:sofa /config /recordings

COPY --from=split /dist/lib /opt/sofa/lib
COPY --from=split /app/bin /opt/sofa/bin
COPY --from=split /app/lib /opt/sofa/lib

# /config holds the database, the logo cache, the generated tokens, and sources.json and policy.json when present.
# Any uid that owns a bind-mounted /config can run the image (user: "UID:GID"); it writes nothing else but /tmp and
# SOFA_RECORDINGS_DIR, which defaults to /config/recordings; Compose sets it to the /recordings mount.
# SOFA_HDHR_ALLOW is required for the LAN bind unless SOFA_HDHR_ENABLED=false; unset API and admin tokens are generated.
# The start script reads SERVER_OPTS (APP_NAME + _OPTS) and JAVA_OPTS; the image sets the former so JAVA_OPTS stays the operator's knob.
# preferIPv4Stack: the HTTP client connects to the first resolved address, and a container without an IPv6 route is still
# handed AAAA records first for CDN hosts.
ENV SOFA_HOST=0.0.0.0 \
    SOFA_PORT=8080 \
    SOFA_DATA_DIR=/config \
    SERVER_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.net.preferIPv4Stack=true"

USER sofa
WORKDIR /opt/sofa
VOLUME /config
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${SOFA_PORT}/readyz" || exit 1

ENTRYPOINT ["/usr/bin/tini", "--", "/opt/sofa/bin/server"]

# Last, so a new revision invalidates no layer above it. GitHub Container Registry shows the description and links the
# package to the repository through the source label; the workflow passes VERSION from the catalog's `sofaking` version.
ARG REVISION=unknown
ARG VERSION=unknown
LABEL org.opencontainers.image.title="Sofa King backend" \
      org.opencontainers.image.description="Live TV server: imports free streaming services and playlists into one lineup, relays the streams and emulates an HDHomeRun for Plex" \
      org.opencontainers.image.url="https://github.com/jamesarich/sofa-king" \
      org.opencontainers.image.documentation="https://github.com/jamesarich/sofa-king/blob/main/backend/docs/deploy.md" \
      org.opencontainers.image.source="https://github.com/jamesarich/sofa-king" \
      org.opencontainers.image.vendor="James Rich" \
      org.opencontainers.image.authors="James Rich" \
      org.opencontainers.image.licenses="GPL-3.0-only" \
      org.opencontainers.image.base.name="docker.io/library/eclipse-temurin:21.0.12.1_1-jre-noble" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.version="${VERSION}"
# Labels are not visible inside the container, so the server reads its revision and version from the environment.
ENV SOFA_REVISION=${REVISION} \
    SOFA_VERSION=${VERSION}
