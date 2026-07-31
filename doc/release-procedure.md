# Release Procedure

Two parts: config changes each environment needs before the new build starts, and the YouTrack
"In release" bookkeeping.

## Config changes to apply before deploying

Each environment keeps its own `application.yaml` outside the repo, so a renamed or added
property will not travel with the build. Spring fails fast on an unresolved `@Value`
placeholder — the context won't start — so a missed entry here is a failed deploy, not a
subtle bug. Add a row whenever a property changes; delete rows once every environment is past
that release.

| Since | Change | Action |
| --- | --- | --- |
| v4.0 (unreleased) | `easy.wui.base-url` renamed to `easy.web.base-url` | Rename the key in every environment's `application.yaml`. Used by `SendMailService` to build links in outgoing email. |
| v4.0 (unreleased) | Core refuses to start when `easy.core.auth-enabled: false` and `server.address` is not loopback (EZ-1724) | No action for a normal deployed environment, which has auth enabled. Only bites an environment that had auth turned off — in which case starting was the bug, not the failure. Local dev needs `server.address: 127.0.0.1`; see DEVELOPMENT.md §4. |
| v4.0 (unreleased) | `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` and `.issuer-uri` added (EZ-1724) | **Required on any environment with `easy.core.auth-enabled: true`** — core now verifies Keycloak tokens itself instead of trusting the Apache OIDC proxy's `oidc_claim_*` headers. Add both keys pointing at that environment's realm; see the sample for why both and not just `issuer-uri`. The matching change on the webserver is to remove the mod_auth_openidc config from the API vhost, leaving a plain `ProxyPass` — do it in the same window, since a vhost that still authenticates will 401 cross-origin preflight `OPTIONS` requests. |

To check an environment before deploying:

```sh
grep -n "wui\|web:" /path/to/application.yaml
```

## YouTrack "In release" field

When a new version is released, update the "In release" version bundle in YouTrack.

## Setting "In release" on an issue

Whenever an issue moves to **Resolved**, set **In release** at the same time — it records the
version the issue was resolved, the feature shipped, or the bug was fixed in. Always choose the
version carrying the **`- next`** suffix; there is exactly one, and it is the upcoming release.

That's what makes the rename below work: stripping the suffix at release time stamps every issue
resolved during the cycle with the real version number, so nothing has to be revisited
issue-by-issue.

Issues resolved long ago may have the field empty. That's history, not the convention.

## Steps

### 1. Rename the current "- next" version (remove suffix + reset color)

```
curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "vX.Y", "color": {"id": "0"}}' \
  "https://easy.youtrack.cloud/api/admin/customFieldSettings/bundles/version/85-2/values/$CURRENT_NEXT_ID?fields=id,name,color(id)"
```

- `color.id "0"` = default (white/no color)
- Find `$CURRENT_NEXT_ID` by listing values first (see below)

### 2. Add the new "- next" version with green color

```
curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "vX.Z - next", "released": false, "archived": false, "color": {"id": "3"}, "$type": "VersionBundleElement"}' \
  "https://easy.youtrack.cloud/api/admin/customFieldSettings/bundles/version/85-2/values?fields=id,name,color(id)"
```

- `color.id "3"` = green (#1F8039 background, #E3F7E7 foreground)
- Note the returned `id` for the next step

### 3. Move the new version to first position in the dropdown

```
curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "vX.Z - next", "ordinal": -100}' \
  "https://easy.youtrack.cloud/api/admin/customFieldSettings/bundles/version/85-2/values/$NEW_ID?fields=id,name,ordinal"
```

- Use a very low ordinal (e.g. -100) to ensure it appears first

## Reference

- Version bundle ID: `85-2` (name: "easy: In releases")
- List all values: `GET /api/admin/customFieldSettings/bundles/version/85-2?fields=values(id,name,ordinal,color(id))`
- Green color ID: `3`
- Default color ID: `0`
- Base URL: `https://easy.youtrack.cloud`
