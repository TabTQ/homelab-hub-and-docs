# Docs images, built on the Docs LXC itself:   docker compose -f deploy/docs/docker-compose.yml --env-file deploy/docs/.env up -d --build
# Multi-stage, so the LXC needs only Docker: Node builds the web bundles, Maven builds the jars, and each runtime
# image gets one jar and a non-root user. Three apps: docs-internal (editor, also edits the portfolio), docs-public (Docs pages)
# and hub-public (the public portfolio, moved here from the Pi; the same code and image recipe as deploy/Dockerfile.public). Tests do not run here: run scripts/build-all.sh on a development machine for those.
# All base images are multi-arch (amd64 and arm64).

FROM node:22-bookworm-slim AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build:docs-public && npm run build:docs-internal && npm run build:public

FROM maven:3.9-eclipse-temurin-17 AS jars
WORKDIR /src
COPY pom.xml ./
COPY hub-snapshot/ hub-snapshot/
COPY hub-auth/ hub-auth/
COPY hub-internal/pom.xml hub-internal/pom.xml
COPY hub-public/ hub-public/
COPY docs-internal/ docs-internal/
COPY docs-public/ docs-public/
COPY --from=web /src/web/dist/docs-internal/ docs-internal/src/main/resources/static/
COPY --from=web /src/web/dist/docs-public/ docs-public/src/main/resources/static/
COPY --from=web /src/web/dist/public/ hub-public/src/main/resources/static/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl docs-internal,docs-public,hub-public -am -DskipTests package \
 && cp docs-internal/target/docs-internal-0.1.0-SNAPSHOT.jar /docs-internal.jar \
 && cp docs-public/target/docs-public-0.1.0-SNAPSHOT.jar /docs-public.jar \
 && cp hub-public/target/hub-public-0.1.0-SNAPSHOT.jar /hub-public.jar

# docs-internal: the editor, its API and login, SQLite and the blob store under /data; writes the Docs snapshot to /snapshot and
# the portfolio snapshot to /portfolio-snapshot.
FROM eclipse-temurin:17-jre AS docs-internal
RUN useradd --system --uid 10003 --home-dir /nonexistent --shell /usr/sbin/nologin docs \
 && mkdir -p /data /snapshot /portfolio-snapshot && chown docs /data /snapshot /portfolio-snapshot
COPY --from=jars /docs-internal.jar /app/docs-internal.jar
USER docs
ENV DOCS_DATA_DIR=/data DOCS_SNAPSHOT_DIR=/snapshot DOCS_PORTFOLIO_SNAPSHOT_DIR=/portfolio-snapshot
VOLUME ["/data", "/snapshot", "/portfolio-snapshot"]
ENTRYPOINT ["java", "-jar", "/app/docs-internal.jar"]

# docs-public: one shared page per hostname, read-only. No database driver, no secrets, nothing writable.
FROM eclipse-temurin:17-jre AS docs-public
# /snapshot owned by docs-internal's uid: a new named volume takes the ownership of the first image that mounts it.
RUN useradd --system --uid 10004 --home-dir /nonexistent --shell /usr/sbin/nologin docspub \
 && mkdir -p /snapshot && chown 10003 /snapshot
COPY --from=jars /docs-public.jar /app/docs-public.jar
USER docspub
ENV DOCS_SNAPSHOT_DIR=/snapshot
ENTRYPOINT ["java", "-jar", "/app/docs-public.jar"]

# hub-public: the public portfolio. Reads only snapshot.json (written by docs-internal) from /snapshot. No database driver, no secrets.
FROM eclipse-temurin:17-jre AS hub-public
# Same rule as docs-public: /snapshot is owned by docs-internal's uid, so the volume works whichever container starts first
# (no depends_on between the apps, so each one starts and restarts on its own).
RUN useradd --system --uid 10002 --home-dir /nonexistent --shell /usr/sbin/nologin hubpub \
 && mkdir -p /snapshot && chown 10003 /snapshot
COPY --from=jars /hub-public.jar /app/hub-public.jar
USER hubpub
ENV HUB_SNAPSHOT_DIR=/snapshot
ENTRYPOINT ["java", "-jar", "/app/hub-public.jar"]
