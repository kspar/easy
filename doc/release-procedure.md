# Release Procedure

Two parts: config changes each environment needs before the new build starts, and the YouTrack
"In release" bookkeeping.

## Config changes to apply before deploying

Each environment keeps its own `application.yaml` outside the repo, so a renamed or added
property will not travel with the build. Spring fails fast on an unresolved `@Value`
placeholder — the context won't start — so a missed entry here is a failed deploy, not a
subtle bug. Add a row whenever a property changes; delete rows once every environment is past
that release.

Since EZ-1726 the same applies to the **frontend**: the web dist reads `config.json` at boot, so
each environment has a second piece of out-of-repo config. Unlike the backend it fails at runtime
rather than at startup, with a "Configuration error" page.

| Since | Change | Action |
| --- | --- | --- |
| v4.0 (unreleased) | `easy.wui.base-url` renamed to `easy.web.base-url` | Rename the key in every environment's `application.yaml`. Used by `SendMailService` to build links in outgoing email. |
| v4.0 (unreleased) | Core refuses to start when `easy.core.auth-enabled: false` and `server.address` is not loopback (EZ-1724) | No action for a normal deployed environment, which has auth enabled. Only bites an environment that had auth turned off — in which case starting was the bug, not the failure. Local dev needs `server.address: 127.0.0.1`; see DEVELOPMENT.md §4. |
| v4.0 (unreleased) | `easy.core.cors.allowed-origins` added (EZ-1727) | **Required on every environment**, or the context won't start. Comma-separated origins allowed to call the API cross-origin — previously a hardcoded list in `SecurityConf` that gave every environment all four origins. Production wants `https://lahendus.ut.ee`; an environment where one proxy fronts both web and API on the same origin wants it empty. Getting this wrong shows up only in a browser, as a CORS error with nothing in the server log, so core logs the configured list at startup. |
| v4.0 (unreleased) | Web reads `config.json` at boot instead of baked-in `VITE_*` (EZ-1726) | **Required on every environment.** Write a `config.json` next to the deployed `index.html` with `emsRoot` and `keycloak.url` / `.realm` / `.clientId` for that environment — the values previously compiled into the bundle. Without it the app shows a "Configuration error" page. **Also serve it with `Cache-Control: no-store`**: the app requests it that way, but a caching layer in front will happily hand a fresh deploy the previous environment's backend URL, and that failure looks like anything but a caching problem. Apache: `<Files "config.json"> Header set Cache-Control "no-store" </Files>`. See `web/README.md`. |
| v4.0 (unreleased) | `easy.core.db.test-data` added; teacher feedback migration fixed | **No key to add — but read this before deploying to production.** Two changelog problems found by importing a production dump into dev on 2026-08-10, both of which bite exactly once, on the first deploy of v4.0 to a database that is behind. (1) `changesets/testdata.xml` was applying local-development fixtures wherever the schema applied; production has never run it, so it would have tried to insert at hardcoded ids and failed the migration, which means core does not start. Those changesets now carry `context="testdata"` and only run when `easy.core.db.test-data` is true, which defaults to false — so production needs nothing, and a local database wants the key from the sample. (2) `v4.xml`'s `260225-1` dropped the jsonb column that `220226-1` had just copied all teacher feedback into, on the reasoning that the jsonb column "was never deployed to prod" — true, and the reason it destroyed the data rather than nothing. It now carries the values across into `feedback_md` / `feedback_html` first. Verified by replaying the fixed sequence against a restored copy of the real table: every feedback text that went in came out, and the grades were never at risk. Both changesets carry `validCheckSum` so databases that ran the old bodies still validate. |
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

## The VERSION file

`VERSION` at the repo root holds the number the running application reports about itself
(EZ-1709) — core reads it in `core/build.gradle.kts`, web in `vite.config.ts`, aae in `server.py`,
and all three show up on the About page as `v4.0 (b14b916)`.

**Bump it when the "- next" version is renamed below**, to the number that release just became, and
commit that on its own. The file is the only place to change: nothing else in the repo carries a
product version, and `web/package.json` deliberately keeps its meaningless `0.0.0`.

The commit hash beside it is stamped by the build (`GITHUB_SHA` in CI, `git rev-parse` locally), so
it needs no maintenance — and it is what distinguishes two builds of the same version, which is the
usual question when a deploy is in doubt.

**`VERSION` does not cover the grading images.** Since EZ-1781 `tiivad`, `silmused`, `pygrader` and
`imgrec` carry their own library versions, pinned in `doc/aae/pins/<environment>.yml` and published
to GHCR by CI independently of any release. A release neither includes them nor needs to wait for
them, and bumping `VERSION` changes nothing about which grader is live.

The one thing a release *should* do is look: the About page lists the installed version of every
grading library beside the component versions, so "which graders is this release going out
alongside" is one page rather than an investigation. `doc/aae/grading-images.md` has the rest,
including how a production promotion works, which is still manual.

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
