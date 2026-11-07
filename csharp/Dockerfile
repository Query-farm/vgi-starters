# syntax=docker/dockerfile:1
# One image, both transports:
#   docker run -p 8000:8000 IMAGE         -> HTTP on $PORT (default 8000; GET /health)
#   docker run -i IMAGE stdio             -> stdio worker, for DuckDB on the same host
FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
ARG TARGETARCH
WORKDIR /src
COPY global.json Directory.Build.props Directory.Packages.props .editorconfig ./
COPY src/Starter.Worker/Starter.Worker.csproj src/Starter.Worker/packages.lock.json src/Starter.Worker/
RUN dotnet restore src/Starter.Worker --locked-mode -a "$TARGETARCH"
COPY src ./src
RUN dotnet publish src/Starter.Worker -c Release --no-restore -a "$TARGETARCH" --self-contained false -o /app

# The worker hosts its HTTP transport on ASP.NET Core, so it needs the aspnet runtime.
FROM mcr.microsoft.com/dotnet/aspnet:10.0

ARG VERSION=0.0.0
ARG GIT_COMMIT=unknown
LABEL org.opencontainers.image.title="vgi-starter-worker" \
      org.opencontainers.image.description="A VGI worker for DuckDB" \
      org.opencontainers.image.source="https://github.com/Query-farm/vgi-starter-csharp" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      org.opencontainers.image.licenses="0BSD"

ENV PORT=8000 DOTNET_CLI_TELEMETRY_OPTOUT=1
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app ./
RUN ln -s /app/vgi-starter-worker /usr/local/bin/vgi-starter-worker
COPY --chmod=0755 docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

# Run as an unprivileged user (the base image's `app` user).
USER $APP_UID
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
    CMD curl -fsS "http://127.0.0.1:${PORT:-8000}/health" >/dev/null || exit 1
ENTRYPOINT ["docker-entrypoint.sh"]
