# syntax=docker/dockerfile:1
# One image, both transports:
#   docker run -p 8000:8000 IMAGE         -> HTTP on $PORT (default 8000; GET /health)
#   docker run -i IMAGE stdio             -> stdio worker, for DuckDB on the same host
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties gradle.lockfile ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon --console=plain dependencies >/dev/null
COPY src ./src
COPY examples ./examples
RUN ./gradlew --no-daemon --console=plain shadowJar

FROM eclipse-temurin:25-jre

ARG VERSION=0.0.0
ARG GIT_COMMIT=unknown
LABEL org.opencontainers.image.title="vgi-starter-worker" \
      org.opencontainers.image.description="A VGI worker for DuckDB" \
      org.opencontainers.image.source="https://github.com/Query-farm/vgi-starter-java" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      org.opencontainers.image.licenses="0BSD"

ENV PORT=8000
WORKDIR /app
COPY --from=build /src/build/libs/vgi-starter-worker-all.jar ./build/libs/
COPY --chmod=0755 bin/vgi-starter-worker ./bin/
COPY --chmod=0755 docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

# Run as an unprivileged user.
RUN useradd --create-home --uid 10001 worker
USER worker

EXPOSE 8000
# bash's /dev/tcp: no curl needed in the image.
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${PORT:-8000} && printf 'GET /health HTTP/1.0\\r\\n\\r\\n' >&3 && head -1 <&3 | grep -q ' 200'"]
ENTRYPOINT ["docker-entrypoint.sh"]
