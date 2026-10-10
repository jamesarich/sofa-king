# Runs the installed distribution built on the host: ./gradlew :server:installDist
# The build context only admits server/build/install/server (see .dockerignore).
FROM eclipse-temurin:21-jre-noble

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 sofa \
    && useradd --system --uid 10001 --gid sofa --home-dir /data --shell /usr/sbin/nologin sofa \
    && mkdir -p /data /config \
    && chown sofa:sofa /data

COPY server/build/install/server /opt/sofa

# SOFA_API_TOKEN (32+ characters) is required for the LAN bind; SOFA_SOURCES unset starts an empty catalog.
ENV SOFA_HOST=0.0.0.0 \
    SOFA_PORT=8080 \
    SOFA_DATA_DIR=/data

USER sofa
WORKDIR /opt/sofa
VOLUME /data
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${SOFA_PORT}/readyz" || exit 1

ENTRYPOINT ["/opt/sofa/bin/server"]
