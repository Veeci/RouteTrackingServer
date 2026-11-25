# Keycloak for the admin dashboard

The identity provider of the admin dashboard (phase 12g, decision D7). `rts-realm.json` defines the realm `rts`:

- The realm roles `rts-admin` and `rts-operator`. The server maps them to ADMIN and OPERATOR (`OIDC_ADMINROLE`,
  `OIDC_OPERATORROLE`). A user with neither role gets 403 `NO_STAFF_ROLE` from the API.
- The public client `rts-admin-web`. It uses the Authorization Code flow with PKCE (S256 required) and has no secret.
  An audience mapper adds `rts-api` to `aud`, which the server requires (`OIDC_AUDIENCE`). The redirect URIs list the
  dev server (5173) and the E2E build (4174).
- Access tokens last 5 minutes. The dashboard refreshes them 60 s before expiry with the refresh token.
- Three users for local development and CI: `operator`, `admin` and `nobody`. Their passwords are in the file and are
  for local use only.

## Run it locally

```bash
docker run --rm --name rts-keycloak -p 8180:8080 -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin -e KC_HOSTNAME=http://localhost:8180 -v "$PWD/deploy/keycloak:/opt/keycloak/data/import:ro" quay.io/keycloak/keycloak:26.8.1 start-dev --import-realm
```

Then start the server with `OIDC_ISSUER=http://localhost:8180/realms/rts`. The admin console is at
http://localhost:8180/admin.

## Before staging or production

This file is for development. A staging or production realm needs these changes first:

- Remove the three test users, and create real staff accounts in the admin console.
- Replace the localhost redirect URIs and post-logout URIs with the dashboard's real URL.
- Run Keycloak with `start`, not `start-dev`, behind HTTPS, with its own database.
- Set `KC_HOSTNAME` to the URL that browsers use. The server checks `iss` against `OIDC_ISSUER`, so both must name the
  same URL. When the server reaches Keycloak at another address, set `OIDC_JWKS` to that address's JWKS URL.
