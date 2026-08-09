# Setting up the dev IdP

`easy-idp-dev.cloud.ut.ee` (193.40.11.153, OpenStack, Ubuntu 24.04.4 LTS) — Keycloak 25.0.2 serving
the realm that every login on staging goes through.

Built from nothing on **2026-08-08**. This document is the whole procedure: what runs the machine,
what had to be decided, and the parts a playbook cannot do. `ansible/roles/keycloak/` is the
executable half; the realm's *contents* are the half that lives here, because they are data rather
than configuration and a playbook that owned them would fight anyone editing the admin console.

Read `doc/staging-environment.md` §7 for why this was the critical path: core deploys and serves
without an IdP — JWT verification fetches the realm's JWKS lazily, so core starts, answers 401, and
only fails when someone actually tries to log in. This host is what turns that 401 into a session.

## Status

**Working end to end as of 2026-08-08.** A real token for `dev-teacher`, obtained from Keycloak over
HTTPS, is accepted by core: `POST /v2/account/checkin` returns 200 and creates the account. A
deliberately malformed token returns 401 on the same endpoint, so the acceptance means verification
happened rather than being skipped.

```
easy-idp-dev.cloud.ut.ee   ->  nginx :443  ->  Keycloak 127.0.0.1:8080/auth  ->  postgres cloakdb
issuer                     https://easy-idp-dev.cloud.ut.ee/auth/realms/master
realm                      master
SPA client                 lahendus.ut.ee     public, PKCE S256
core's client              easy-core          confidential, service account, view-users only
role claim                 easy_role          client roles on lahendus.ut.ee
login theme                lahendus
```

---

## 0. Rules

- **`easy_core_idp_base_url` is the origin only — no path.** core appends `/auth` itself in
  `delete_inactive_users.kt`. A value ending in `/auth` gives `/auth/auth/...`. (§6)
- **The admin client in `master` is `master-realm`, not `realm-management`.** Every Keycloak guide
  says the latter, because every guide assumes a dedicated realm. (§4.4)
- **`http-relative-path` also applies to the management port.** Readiness is
  `:9000/auth/health/ready`, not `:9000/health/ready`. (§3.3)
- **The service account must never hold `manage-users`.** It is the second line of defence behind the
  pinned deletion cron, and the one that survives someone unpinning it. (§4.4)
- **Credentials live on the host and nowhere else.** `/etc/keycloak/keycloak.env` is the only copy of
  the database and admin passwords. **Back it up.** (§2)
- **Users need an email address.** core rejects a token without one, so a user created without email
  logs into Keycloak fine and then fails every API call. (§4.7)

---

## 1. What was there first

The VM had been reimaged and a home directory restored onto it from `home-idp.zip` on 2026-08-07.
That restore carried the Keycloak *install* and the theme, and nothing else:

| Found | Meaning |
| --- | --- |
| `~kspar/keycloak-25.0.2/` unpacked, plus the zip | The distribution, untouched since July 2024 |
| `conf/keycloak.conf` pointing at `jdbc:postgresql://localhost:5432/cloakdb` | **The realm was never in this directory.** It was in a Postgres database that did not exist on this host |
| No postgres installed at all | So the realm data did not survive, and there was nothing to restore |
| No JVM installed | `./bin/kc.sh` answered `java: not found` |
| A database password in plaintext, world-readable, in that conf file | Removed by the role now; see §2 |
| `~kspar/easy-kc-theme/` | The lahendus theme, last touched 2023 |
| `hostname=dev.idp.lahendus.ut.ee` in the conf | **A name that has never served this IdP** — see §5 |

That answers the open question in `doc/staging-environment.md` §7. The realm was not on disk, because
it was never on disk. **Rebuilt from scratch** (§4) rather than restored — there was no dump.

---

## 2. Where the credentials live

Nowhere in this repo, and nowhere on your laptop. The same property `ansible/README.md` describes for
core, for the same reasons.

`/etc/keycloak/keycloak.env` is 0640 root:keycloak and holds three values:

```
KC_DB_PASSWORD             generated on the host, never leaves it
KEYCLOAK_ADMIN             admin
KEYCLOAK_ADMIN_PASSWORD    generated on the host — this is how you first log in
```

The role creates the file if it is missing, generates what it can, and afterwards only ever `stat`s
it — it checks the mode and the owner, never the contents. So no credential reaches the controller,
there is nothing to encrypt and nothing to hand to a colleague.

systemd reads it as root via `EnvironmentFile=` before dropping to the service account, so the
account running Keycloak never needs read access to the file itself.

Read the admin password when you need it:

```sh
ssh easyidpdev 'sudo grep KEYCLOAK_ADMIN_PASSWORD /etc/keycloak/keycloak.env'
```

**The cost, stated plainly:** rebuilding this host from nothing needs these from somewhere else, and
Ansible cannot rotate what it cannot read. Back the file up. It is four lines.

Test-account passwords are in `/etc/keycloak/test-users.txt`, 0600 root. Those are disposable —
delete the file and the users and re-run §4.7.

**One credential does cross a machine:** the `easy-core` client secret is minted on the IdP and
consumed by core, so it has to be copied to the core host's `secrets.yaml`. §6 does that without it
touching a command line or a shell history.

---

## 3. The machine

```sh
cd ansible
./run.sh site.yml --check --diff --limit easyidpdev   # dry run
./run.sh site.yml --limit easyidpdev                  # apply
```

`roles/keycloak/` does all of it: JVM, postgres, the server, the unit, the theme, nginx and TLS. The
role is idempotent — a converged host reports `changed=0`. Its `defaults/main.yml` carries the
reasoning for each value; what follows is only what does not fit in a comment.

### 3.1 The version is pinned and checksummed

`keycloak_version: 25.0.2`, downloaded from GitHub and verified against a SHA-256 in the role.

That checksum was not taken on trust. The copy already sitting in `~kspar` was compared against a
fresh download of the official release asset on the host, and they matched — which is what turned
that file's provenance from "presumably fine" into a known quantity. Keycloak upgrades change realm
storage, so "whatever is latest" is not something this role should ever install by itself.

If the download ever fails on a checksum mismatch, **do not fix it by updating the hash.**

### 3.2 Java 21, not Java 25

Keycloak 25 runs on Java 17 and 21. Core runs on 25 — a different host, and deliberately not the same
runtime. The role asserts the version, because the failure otherwise is a Quarkus startup crash that
says nothing about Java.

`JAVA_HOME` is discovered by globbing `/usr/lib/jvm/java-21-openjdk-*` rather than written down:
Debian suffixes that path with the architecture, and a hardcoded `amd64` is a role that works here
and fails on the next host.

### 3.3 It is built, then started `--optimized`

Keycloak has two kinds of option. *Build* options (`db`, `cache`, `features`) are baked in by
`kc.sh build`; *runtime* options (`hostname`, credentials, log level) are read at startup. The unit
runs `kc.sh start --optimized`, so the server uses the baked-in build instead of re-deriving it on
every boot — which is both faster and honest: a build option changed without a rebuild fails loudly
instead of running the previous value.

The role's handler therefore rebuilds *and* restarts, always together.

**Readiness is at `:9000/auth/health/ready`.** `http-relative-path` applies to the management
interface as well as the main one, which the option's name does not suggest. `:9000/health/ready` is
a 404. This cost thirty retries against a server that had been healthy the whole time.

### 3.4 `cache=local`, and why it matters more than it looks

With the default (`ispn`), Keycloak starts a JGroups stack for cache replication and binds it to
**0.0.0.0 on a random ephemeral port** — 36346 on the first run here. ufw's default-deny kept it off
the internet, but a firewall rule cannot name a port that changes on every restart, and `smoke.yml`
flags it as a public listener every run.

There is one Keycloak here and no second node to replicate to, so the whole stack was cost without
benefit. `cache=local` removes the listener.

Revisit if this host is ever clustered — that wants `ispn` plus a *pinned* JGroups port and a ufw rule
scoped to the peers, not a return to the default.

### 3.5 nginx, and the header that is a security boundary

Keycloak binds 127.0.0.1 and nginx terminates TLS. `proxy-headers=xforwarded` in `keycloak.conf`
tells Keycloak to believe `X-Forwarded-Proto` and `X-Forwarded-Host` when it builds redirects and the
`issuer` claim.

That is only safe because the vhost **sets** those headers rather than passing through whatever the
client sent. A Keycloak that trusted a client-supplied `X-Forwarded-Host` would let anyone choose the
issuer in the tokens it mints. If you ever rewrite that vhost, this is the part to get right.

Not `proxy=edge`, which is what the old conf said and what most guides still show: deprecated in
Keycloak 24, removed in 26.

### 3.6 nginx is duplicated between two roles, on purpose for now

`roles/nginx` serves the SPA and proxies the API on the core host; `roles/keycloak` has its own
80-line vhost for this one. Folding them together would mean a role parameterised by a list of vhost
shapes, which is more machinery than two templates. **If a third vhost appears, reconsider** — the
executor is the likely candidate.

---

## 4. The realm

Built by hand, deliberately: realms are data, they change without a release, and an Ansible role that
owned them would either fight an admin using the console or silently revert their work.

The commands below are idempotent and safe to re-run. Run them on the host as root — they read the
admin password from `/etc/keycloak/keycloak.env`, so it is never typed, never in an argument (visible
in `ps` for every user on the box), and never in a shell history.

```sh
ssh easyidpdev
sudo -i
export JAVA_HOME=$(ls -d /usr/lib/jvm/java-21-openjdk-* | head -1)
export HOME=/root
K=/opt/keycloak/bin/kcadm.sh
$K config credentials --server http://127.0.0.1:8080/auth --realm master \
  --user "$(grep '^KEYCLOAK_ADMIN=' /etc/keycloak/keycloak.env | cut -d= -f2-)" \
  --password "$(grep '^KEYCLOAK_ADMIN_PASSWORD=' /etc/keycloak/keycloak.env | cut -d= -f2-)"
```

### 4.1 Why `master`, which is not best practice

The application realm is Keycloak's own admin realm. That is not what anyone would choose fresh, and
it is kept anyway because **production does the same** and staging is meant to be the release gate,
not a third convention.

It is also cheaper than the alternative in a specific way: `delete_inactive_users.kt` hardcodes
`realms/master` in `getAccessToken()` while using the configured `$realm` for the admin calls. Those
two disagree the moment the realm is not `master`, so moving to a dedicated realm is a code change,
not a config change. **That half is now done** (`10f169c1`), so what remains is the realm itself.

### What it costs, concretely

Every application user is a user *in the realm whose admin console lives at `/auth/admin/`*. So an
ordinary teacher or student can reach that console and authenticate against it. They have no admin
rights, which works correctly — `/auth/admin/serverinfo` answers 403 — but Keycloak's console does
not handle that answer: it renders a **blank page with two spinners, indefinitely**, rather than
saying they do not have access.

Verified 2026-08-09 in a real browser: as `dev-teacher`, blank page and a 403 on `serverinfo`; as
`admin`, the console loads completely. So authorization is right and the UI is not. Nothing here is
misconfigured, and there is nothing to fix on this side — patching it would mean maintaining a
custom admin theme.

With a dedicated realm the situation does not arise: application users would live in `easy`, the
console at `/auth/admin/` belongs to `master`, and an application user could not log into it at all.
That is a better argument for the move than tidiness, and it is why this is written down here rather
than filed as a bug against something we cannot change.

The account console (`/auth/realms/master/account/`), which is the one this application actually
links to from its settings page, works correctly for ordinary users.

### 4.2 The realm settings

```sh
$K update realms/master \
  -s registrationAllowed=false \
  -s resetPasswordAllowed=false \
  -s rememberMe=true \
  -s bruteForceProtected=true \
  -s loginTheme=lahendus
```

Registration off is the decision from `doc/staging-environment.md` §7: accounts are admin-created,
which removes the "anyone with a UT account wanders into staging" problem entirely.

### 4.3 The SPA's client

```sh
$K create clients -r master \
  -s clientId=lahendus.ut.ee \
  -s enabled=true -s publicClient=true \
  -s standardFlowEnabled=true \
  -s directAccessGrantsEnabled=false \
  -s 'attributes."pkce.code.challenge.method"=S256' \
  -s rootUrl=https://dev.lahendus.ut.ee \
  -s 'redirectUris=["https://dev.lahendus.ut.ee/*"]' \
  -s 'webOrigins=["https://dev.lahendus.ut.ee"]'
SPA=$($K get clients -r master -q clientId=lahendus.ut.ee --fields id --format csv --noquotes | head -1)
```

Public with PKCE, matching what `keycloak-js` in the SPA does. `directAccessGrantsEnabled=false` on
purpose — the password grant trades a password for a token with no redirect, which is not how this
application logs anyone in.

### 4.4 Roles, and the claim core actually reads

**Client roles on the SPA client, deliberately not realm roles.**

A realm-role mapper emits *every* realm role the user holds. In a fresh realm that means
`default-roles-master`, `offline_access` and `uma_authorization` arrive in the claim alongside the
real ones — and `mapRoleStringsToRoles` throws `Unmapped role` on the first one it does not
recognise, rejecting the token outright. A client-role mapper emits only that client's roles, so the
claim holds exactly `student`/`teacher`/`admin`.

```sh
for role in student teacher admin; do $K create clients/$SPA/roles -r master -s name=$role; done

$K create clients/$SPA/protocol-mappers/models -r master \
  -s name=easy_role \
  -s protocol=openid-connect \
  -s protocolMapper=oidc-usermodel-client-role-mapper \
  -s 'config."usermodel.clientRoleMapping.clientId"=lahendus.ut.ee' \
  -s 'config."claim.name"=easy_role' \
  -s 'config."jsonType.label"=String' \
  -s 'config."multivalued"=true' \
  -s 'config."access.token.claim"=true' \
  -s 'config."id.token.claim"=false' \
  -s 'config."userinfo.token.claim"=true'
```

core reads exactly this claim in `EasyUserJwtConverter.kt`. Without the mapper every login succeeds at
Keycloak and every API call 401s.

Then core's own client, and the grant that matters:

```sh
$K create clients -r master -s clientId=easy-core -s enabled=true \
  -s publicClient=false -s standardFlowEnabled=false \
  -s directAccessGrantsEnabled=false -s serviceAccountsEnabled=true
CORE=$($K get clients -r master -q clientId=easy-core --fields id --format csv --noquotes | head -1)
SA=$($K get clients/$CORE/service-account-user -r master --fields id --format csv --noquotes | head -1)

# `master-realm`, NOT `realm-management`. The admin client is called realm-management in every realm
# except master, and every guide online assumes a dedicated realm.
$K add-roles -r master --uid $SA --cclientid master-realm --rolename view-users
$K remove-roles -r master --uid $SA --cclientid master-realm --rolename manage-users   # must not hold this
```

**`view-users` and never `manage-users`**, from `doc/staging-environment.md` §5. The reason is
specific rather than general caution: core's `DeleteInactiveUsers` cron deletes accounts from the
database *and* from Keycloak, and staging's imported `last_seen` values are historical, so an unpinned
run would delete a large slice of the import from both. The cron is pinned to the never-date as the
first defence. This is the second, and it is the one that still holds when someone unpins the cron
without reading why it was pinned.

Verify it, rather than assuming:

```sh
$K get users/$SA/role-mappings/clients/$($K get clients -r master -q clientId=master-realm \
  --fields id --format csv --noquotes | head -1) -r master --fields name --format csv --noquotes
# expect exactly: view-users
```

### 4.5 The theme, and what three years of Keycloak did to it

`lahendus` is installed from `~kspar/easy-kc-theme` into `/opt/keycloak/themes/`, as a directory
rather than a jar so it can be edited and reloaded without a build step.

It was written for Keycloak 15 and had never run on 25. It mostly works — the login form renders and
there are no FreeMarker errors — but it asked for three stylesheets that Keycloak 25 does not ship,
and a 404 stylesheet is an unstyled page rather than a visible error:

| Asked for | Reality on 25 |
| --- | --- |
| `web_modules/@patternfly/react-core/dist/styles/base.css` | No `web_modules` directory at all |
| `web_modules/@patternfly/react-core/dist/styles/app.css` | Same |
| `css/tile.css` | Not in this theme, not in its parent |

The role rewrites `styles` and `stylesCommon` in the installed copy to the paths Keycloak 25's own
`keycloak/login/theme.properties` uses. Both edits are idempotent. All eight assets now return 200.

**The theme has no home, and that is the real problem.** It came from a home-directory restore, it is
in no repository, and a rebuilt host gets the 2023 copy again. The role copies it only if it is not
already installed, so to pick up an edited source: remove
`/opt/keycloak/themes/lahendus` and re-run. **Vendoring it into this repo is outstanding work.**

To fall back to the stock login page, no playbook run needed:

```sh
$K update realms/master -s loginTheme=keycloak
```

### 4.6 The admin-console gate

`https://easy-idp-dev.cloud.ut.ee/idp-admin/` is a page of ours that stands in front of the admin
console, because the console's own answer to "signed in, but not an admin" is a blank page with two
spinners (§4.1). It gets a token, asks `/auth/admin/serverinfo` a question only an admin may ask, and
branches on the answer: **200 → straight to the console**, **403 → say so, and offer to sign out and
use another account** (or to go to the account page, which is what someone looking for "Keycloak"
usually wanted).

It tests the capability rather than a role name on purpose. The roles that grant console access are
spelled differently in `master` than elsewhere, and a hardcoded list would be one more thing to get
wrong on a page whose entire job is to prevent a confusing failure.

`roles/keycloak` installs the page and the nginx location; the client it needs is realm data, so it
lives here like every other client:

```sh
HOST=easy-idp-dev.cloud.ut.ee
$K create clients -r master \
  -s clientId=idp-admin-gate -s enabled=true -s publicClient=true \
  -s standardFlowEnabled=true -s directAccessGrantsEnabled=false \
  -s 'attributes."pkce.code.challenge.method"=S256' \
  -s rootUrl="https://$HOST" \
  -s "redirectUris=[\"https://$HOST/idp-admin/*\"]" \
  -s "webOrigins=[\"https://$HOST\"]"
```

A separate client rather than reusing `security-admin-console`, whose redirect URIs are locked to
`/admin/master/console/*` — widening Keycloak's own furniture to admit a page of ours buys nothing.
The role checks the client answers a login attempt and reports it on every run if not, so a missing
client shows up as a message rather than as a broken page.

**It does not intercept `/auth/admin/`.** Keycloak's own URLs behave exactly as they do out of the
box, and nothing here can break the console for an administrator; the cost is that typing
`/auth/admin/` directly still hangs for a non-admin. Deliberate — see the decision in §4.1.

**Delete this when the realm moves.** Under a dedicated realm application users cannot authenticate
to the master console at all, and the gate becomes a page guarding a door nobody can reach.

### 4.7 Accounts

Registration is off, so accounts are created by an admin. **Every user needs an email address** —
`EasyUserJwtConverter` rejects a token missing `preferred_username`, `email` or `easy_role` with a
401, so a user created without one logs into Keycloak perfectly well and then fails every single API
call. That looks like a core bug and is not one.

```sh
$K create users -r master -s username=dev-teacher -s enabled=true -s emailVerified=true \
  -s email=dev-teacher@dev.lahendus.ut.ee -s firstName=Devi -s lastName=Teacher
UID_=$($K get users -r master -q username=dev-teacher -q exact=true --fields id --format csv --noquotes | head -1)
$K set-password -r master --userid $UID_ --new-password '...'
$K add-roles -r master --uid $UID_ --cclientid lahendus.ut.ee --rolename teacher
```

`dev-student`, `dev-teacher` and `dev-admin` exist, one per role; passwords in
`/etc/keycloak/test-users.txt` (0600 root).

**To test as a teacher with real course data**, create a user whose **username matches** a teacher row
in the imported data — `doc/staging-environment.md` §3.4. That tester then inhabits that (anonymised)
teacher's courses, which is the deliberate, auditable way to get realistic access.

---

## 5. The hostname, which was wrong everywhere

Every config in this repo pointed the IdP at **`dev.idp.lahendus.ut.ee`**. That name is a CNAME to
`proxy.hpc.ut.ee` (193.40.46.68/69) — a host that has never served this IdP. It could not have worked.

The VM answers to **`easy-idp-dev.cloud.ut.ee`**, which resolves to 193.40.11.153.

This matters more than a rename usually does, because Keycloak's `hostname` decides the `issuer`
claim in every token, and core rejects any token whose issuer is not the configured one. The role
asserts the two agree after every run.

Changed in: `ansible/inventories/staging/group_vars/all/core.yml`, `deploy/staging/config.json`, and
the deployed `config.json` on the core host (a redeploy would place the same file).

`web/public/config.json` is **unchanged** — it holds production's values, not staging's.

---

## 6. Pointing core at it

Three things, of which the second is a bug that had been live and invisible.

### 6.1 The base URL is the origin only

```yaml
easy_core_idp_base_url: https://easy-idp-dev.cloud.ut.ee    # NO path
easy_core_idp_path_prefix: /auth                            # added by the template
easy_core_keycloak_realm: master
easy_core_keycloak_client_id: easy-core
```

`easy_core_idp_base_url` used to be `https://dev.idp.lahendus.ut.ee/auth`, and one variable was doing
two incompatible jobs:

- `jwk-set-uri` / `issuer-uri` are built as `{base}/realms/...` and **need** the `/auth` prefix.
- `easy.core.keycloak.base-url` is consumed by `delete_inactive_users.kt`, which appends `/auth`
  **itself** — in all three of `getAccessToken`, `getKeycloakUserId` and `deleteKeycloakUser`.

So the old value made JWKS correct and every admin-API call `/auth/auth/...`, which 404s. Nobody saw
it because staging pins the cron that drives those calls to a date that never comes, so the broken URL
was never built.

Fixed by splitting the prefix into `easy_core_idp_path_prefix` and adding an assert in `core_config`
that refuses a base URL with a path on it — the one property of that value that its name does not
suggest.

**Still true in the code:** `getAccessToken()` hardcodes `realms/master` while the other two use
`$realm`. Harmless while the realm is `master`; a trap for whoever changes it. See §4.1.

### 6.2 The client secret, which has to cross a machine

Minted on the IdP, consumed by core — the one credential in this system that is not generated where it
is used. Copy it without it reaching a command line:

```sh
# on the IdP host, as root
CORE=$($K get clients -r master -q clientId=easy-core --fields id --format csv --noquotes | head -1)
$K get clients/$CORE/client-secret -r master --fields value --format csv --noquotes
```

then put it in `easy.core.keycloak.client-secret` in `/srv/easy/conf/secrets.yaml` on the **core**
host, alongside the database password. Do not create that file if it is missing — it is the only copy
of core's database password, and a plausible-looking new one in the wrong place is worse than an
error. (This is not hypothetical: a guessed path produced a second `secrets.yaml` in
`/srv/easy/core/` while core kept reading the real one in `/srv/easy/conf/`.)

Then apply and restart:

```sh
cd ansible && ./run.sh site.yml --limit easycoredev
```

### 6.3 Verify the whole chain, not the pieces

```sh
# a real token, and core's answer to it
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://dev.ems.lahendus.ut.ee/v2/account/checkin \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"first_name":"Devi","last_name":"Teacher"}'      # expect 200

# and prove the 200 means something
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://dev.ems.lahendus.ut.ee/v2/account/checkin \
  -H 'Authorization: Bearer not.a.token' -H 'Content-Type: application/json' -d '{}'   # expect 401
```

Getting `$TOKEN` without a browser means enabling the password grant on the SPA client for the length
of one request. **Turn it back off**, and check that you did:

```sh
$K update clients/$SPA -r master -s directAccessGrantsEnabled=true
# ... fetch the token ...
$K update clients/$SPA -r master -s directAccessGrantsEnabled=false
$K get clients/$SPA -r master --fields directAccessGrantsEnabled --format csv --noquotes   # expect false
```

A 400 complaining about a missing body field is a **pass** for auth purposes: core got far enough to
validate the request body, which is past the point where a bad token stops.

---

## 7. Checking it later

```sh
cd ansible && ./run.sh smoke.yml --limit easyidpdev
```

`smoke.yml` is read-only. It checks the sshd posture, ufw, the fail2ban jail, NTP, certificate
expiry, disk, and — the one most likely to earn its keep — that nothing listens on a public address
except 22, 80 and 443. That last check is what would have caught §3.4's JGroups port.

Directly:

```sh
curl -s https://easy-idp-dev.cloud.ut.ee/auth/realms/master/.well-known/openid-configuration | jq .issuer
ssh easyidpdev 'systemctl is-active keycloak; curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:9000/auth/health/ready'
```

The admin console is at <https://easy-idp-dev.cloud.ut.ee/auth/admin/>.

---

## 8. Still to do

- **Vendor the theme** — EZ-1744 (Vendor the lahendus Keycloak theme — it exists only in a home
  directory on one VM). Also fold the Keycloak 25 stylesheet corrections into the source and delete
  the two `lineinfile` tasks that currently apply them after the copy (§4.5).
- **Back up `cloakdb` and `/etc/keycloak/keycloak.env`** — EZ-1745 (Back up the IdP: cloakdb and
  /etc/keycloak/keycloak.env are both single copies). This document reproduces the realm; it does not
  reproduce the users in it, so today the IdP is rebuildable and logins are not (§2).
- **A dedicated realm instead of `master`** (§4.1). The `getAccessToken()` half is **done** — all
  three admin URLs now follow `easy.core.keycloak.realm` — so what remains is the realm itself, the
  config in three places, and production doing the same so the two do not diverge.
- **Production.** `easyidpprod` (193.40.22.67) has never been touched by this role. The role takes
  its hostname from the inventory and has no staging assumptions in it, but production is running an
  older Keycloak whose realm holds real accounts — that is a migration, not an apply.

Not this document's, but found here and affecting this host: EZ-1746 (A reboot-required left by
manual apt is never acted on, so hosts run indefinitely on superseded kernels).
