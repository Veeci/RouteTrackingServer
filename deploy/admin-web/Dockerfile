# syntax=docker/dockerfile:1.7
# The admin dashboard (phase 12f): the built app on nginx, which also proxies /api and /ws to the server's admin port.
# Build from the repo root, because the build generates the API types from app/src/main/resources/openapi/rts-v1.yaml:
#   docker build -f deploy/admin-web/Dockerfile --build-arg GIT_SHA=$(git rev-parse HEAD) -t route-tracking-admin-web .

# ---- build stage ------------------------------------------------------------
# The same Node major as admin-web/.nvmrc. CI's tests run on that version.
FROM node:24.22.3-alpine AS build
WORKDIR /workspace/admin-web

# 1) Dependencies first, so they stay cached until the lock file changes. Exact versions from package-lock.json.
COPY admin-web/package.json admin-web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund

# 2) The contract and the sources, then the build. Tests, lint and the typecheck run in the `Admin web` CI job.
COPY app/src/main/resources/openapi/rts-v1.yaml /workspace/app/src/main/resources/openapi/rts-v1.yaml
COPY admin-web/ ./
RUN npm run generate && npm run build

# 3) The security headers from csp.ts, the one source of the policy, as nginx lines.
RUN mkdir -p /out && node csp.ts --nginx > /out/security-headers.conf

# 4) version.json: which commit this is, and where MapLibre's worker landed. The smoke test reads both. The worker is
#    the one .mjs file of the build, and the build fails here if it is missing or not alone.
ARG GIT_SHA=dev
RUN set -eu; \
    worker="$(cd dist && ls assets/*.mjs)"; \
    [ "$(printf '%s\n' "$worker" | wc -l)" -eq 1 ] || { echo "expected one .mjs file in dist/assets, got: $worker" >&2; exit 1; }; \
    printf '{"sha":"%s","worker":"/%s"}\n' "$GIT_SHA" "$worker" > dist/version.json

# ---- runtime stage ----------------------------------------------------------
# Runs as user nginx (101) and listens on 8080, so the container needs no root and no privileged port.
FROM nginxinc/nginx-unprivileged:1.30.6-alpine AS runtime

COPY deploy/admin-web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /out/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /workspace/admin-web/dist /usr/share/nginx/html

# Fails the build on a config error, instead of at the first start on the laptop.
RUN nginx -t

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
