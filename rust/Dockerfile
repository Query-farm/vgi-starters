# syntax=docker/dockerfile:1
# One image, both transports:
#   docker run -p 8000:8000 IMAGE         -> HTTP on $PORT (default 8000; GET /health)
#   docker run -i IMAGE stdio             -> stdio worker, for DuckDB on the same host
FROM rust:1.97-slim-bookworm AS build
WORKDIR /src
COPY Cargo.toml Cargo.lock README.md LICENSE ./
COPY src ./src
COPY examples ./examples
RUN cargo build --locked --release --bin vgi-starter-worker

FROM debian:bookworm-slim

ARG VERSION=0.0.0
ARG GIT_COMMIT=unknown
LABEL org.opencontainers.image.title="vgi-starter-worker" \
      org.opencontainers.image.description="A VGI worker for DuckDB" \
      org.opencontainers.image.source="https://github.com/Query-farm/vgi-starter-rust" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      org.opencontainers.image.licenses="0BSD"

# curl is only for the HEALTHCHECK; ca-certificates lets fetch_items reach https:// APIs.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/target/release/vgi-starter-worker /usr/local/bin/vgi-starter-worker

# Run as an unprivileged user.
RUN useradd --create-home --uid 10001 worker
USER worker
ENV PORT=8000

COPY --chmod=0755 docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
    CMD curl -fsS "http://127.0.0.1:${PORT:-8000}/health" >/dev/null || exit 1
ENTRYPOINT ["docker-entrypoint.sh"]
