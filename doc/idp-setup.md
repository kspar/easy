# Setting up the dev IdP

`dev.idp.lahendus.ut.ee` — a CNAME to `easy-idp-dev.cloud.ut.ee`, the VM's own name (193.40.11.153,
OpenStack, Ubuntu 24.04.4 LTS) — Keycloak 26.7.2 serving the realm that every login on dev goes
through. Both names reach it; the `lahendus.ut.ee` one is what the configs use and what the tokens
say, and §5 is why that took two attempts.

Built from nothing on **2026-08-08**. This document is the whole procedure: what runs the machine,
what had to be decided, and the parts a playbook cannot do. `ansible/roles/keycloak/` is the
executable half; the realm's *contents* are the half that lives here, because they are data rather
than configuration and a playbook that owned them would fight anyone editing the admin console.

Read `doc/dev-environment.md` §7 for why this was the critical path: core deploys and serves
without an IdP — JWT verification fetches the realm's JWKS lazily, so core starts, answers 401, and
only fails when someone actually tries to log in. This host is what turns that 401 into a session.

## Status

**Working end to end as of 2026-08-08.** A real token for `dev-teacher`, obtained from Keycloak over
HTTPS, is accepted by core: `POST /v2/account/checkin` returns 200 and creates the account. A
deliberately malformed token returns 401 on the same endpoint, so the acceptance means verification
happened rather than being skipped.

**Renamed 2026-08-21** to `dev.idp.lahendus.ut.ee`, now that the alias points here (§5), and applied
the same day. Verified end to end: a token minted by the renamed IdP carries
`iss=https://dev.idp.lahendus.ut.ee/auth/realms/master`, and core resolves a user from it
(`user=service-account-easy-dev-test-runner` in its log) while a tampered copy of the same token gets
401 — so the acceptance means verification happened. The certificate for the old name has been
deleted and the gate client no longer lists it. §5.1 is the procedure, if this is ever done again.

```
dev.idp.lahendus.ut.ee     ->  nginx :443  ->  Keycloak 127.0.0.1:8080/auth  ->  postgres cloakdb
issuer                     https://dev.idp.lahendus.ut.ee/auth/realms/master
realm                      master
SPA client                 lahendus.ut.ee     public, PKCE S256
core's client              easy-core          confidential, service account, view-users only
gate client                idp-admin-gate     public, PKCE S256 — see below
role claim                 easy_role          client roles on lahendus.ut.ee
login theme                lahendus
```

### The three URLs worth knowing

| | |
| --- | --- |
| `/idp-admin/` | **Start here for admin work.** Sends you to the console if your account may use it, and says so plainly if it may not (§4.6) |
| `/auth/admin/` | The console itself. Works for admins; for anyone else it is a blank page with two spinners, forever — which is why the above exists |
| `/auth/realms/master/account/` | Your own password, email and 2FA. Needs no administrator, and is what the application links to from its settings page |

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
| `hostname=dev.idp.lahendus.ut.ee` in the conf | **A name that did not resolve to this host** — it does again since 2026-08-21, see §5 |

That answers the open question in `doc/dev-environment.md` §7. The realm was not on disk, because
it was never on disk. **Rebuilt from scratch** (§4) rather than restored — there was no dump.

---

## 2. Where the credentials live

Nowhere in this repo, and nowhere on your laptop. The same property `ansible/README.md` describes for
core, for the same reasons.

`/etc/keycloak/keycloak.env` is 0640 root:keycloak and holds three values:

```
KC_DB_PASSWORD                 generated on the host, never leaves it
KC_BOOTSTRAP_ADMIN_USERNAME    admin
KC_BOOTSTRAP_ADMIN_PASSWORD    generated on the host — this is how you first log in
```

Keycloak 25 called the last two `KEYCLOAK_ADMIN` and `KEYCLOAK_ADMIN_PASSWORD`; 26 renamed them and
ignores the old spelling without saying so. The role renames them in place on a host it set up
earlier, keeping the values — the password in that file is the one the database was bootstrapped
with, and a freshly generated one would open nothing.

**Bootstrap means bootstrap.** Keycloak reads these only against an empty database. On a host whose
database was migrated from an older Keycloak — production — the admin account is whatever it already
was, and these values name an account that does not exist. Log in with the real one.

The role creates the file if it is missing, generates what it can, and afterwards only ever `stat`s
it — it checks the mode and the owner, never the contents. So no credential reaches the controller,
there is nothing to encrypt and nothing to hand to a colleague.

systemd reads it as root via `EnvironmentFile=` before dropping to the service account, so the
account running Keycloak never needs read access to the file itself.

Read the admin password when you need it:

```sh
ssh easyidpdev 'sudo grep KC_BOOTSTRAP_ADMIN_PASSWORD /etc/keycloak/keycloak.env'
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

`keycloak_version: 26.7.2`, downloaded from GitHub and verified against a SHA-256 in the role.

Keycloak publishes no `.sha256` next to the zip, so that hash is one this project computed off the
release asset rather than an upstream figure it agrees with. What it buys is not "this is the real
Keycloak" but "every host installs the same bytes, and one that does not gets a failure instead of a
surprise". Keycloak upgrades change realm storage, so "whatever is latest" is not something this
role should ever install by itself.

If the download ever fails on a checksum mismatch, **do not fix it by updating the hash.**

26.7.2 arrived here as a security upgrade, not a routine bump: it fixes CVE-2026-18963, an
unauthenticated account takeover through the reset-credentials flow, and seven others.

### 3.2 Java 21, not Java 25

Keycloak 26.7 runs on Java 17 and 21. Core runs on 25 — a different host, and deliberately not the same
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

### 3.7 Two locations that exist for a retired client, and are meant to be deleted

`keycloak_legacy_adapter_enabled` adds an adapter file and one CORS exception to the vhost. Both are
for Thonny plugin installs older than 10.0.0, which log in through a JavaScript adapter fetched from
this host and exchange the authorization code **in the browser**, from
`http://127.0.0.1:<random port>`. Keycloak 26 broke that twice over:

- `{relative path}/js/keycloak.js` is gone — the adapter moved to npm on its own release cycle, and
  the endpoint 404s. The old page's `new Keycloak(...)` then hits an undefined global, which leaves a
  blank page and a plugin that waits five minutes for a login that cannot happen (EZ-1803, EZ-1880).
- Even with the adapter back, the exchange is refused: `POST .../token` with
  `Origin: http://127.0.0.1:56928` answers **403 `{"error":"Invalid origin"}`**, while the same
  request without an `Origin` header gets as far as `400 invalid_grant`. The client's Web Origins is
  the site's own name, and Keycloak matches those exactly — a wildcard port is not something it
  accepts, so `*` would be the only value that worked.

So the vhost serves `keycloak-js` **25.0.6** (the last release with a UMD build, since the old page
is a `<script src>` expecting a global) at that path, and gives the realm's token endpoint — that URI
and no other — an exception: for a loopback origin only, nginx drops the `Origin` before proxying and
answers the CORS half itself. Every other origin reaches Keycloak untouched and still gets a 403.

What it costs: a page running on the user's own machine can read token-endpoint responses for the
public client. PKCE and the registered redirect URIs are that client's real boundary — CORS was never
load-bearing for a flow that runs on 127.0.0.1 — which is why this is scoped to one URI and one
origin shape instead of widening Web Origins on a production client.

**This is a stopgap with an expiry.** Turn the flag off once the old installs are gone: the tasks take
the file away and the vhost stops shadowing a path that belongs to Keycloak. Pinning a retired adapter
major to serve a retired flow is not something to carry, and EZ-1880 is the fix that makes it
unnecessary.

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
  --user "$(grep '^KC_BOOTSTRAP_ADMIN_USERNAME=' /etc/keycloak/keycloak.env | cut -d= -f2-)" \
  --password "$(grep '^KC_BOOTSTRAP_ADMIN_PASSWORD=' /etc/keycloak/keycloak.env | cut -d= -f2-)"
```

### 4.1 Why `master`, which is not best practice

The application realm is Keycloak's own admin realm. That is not what anyone would choose fresh, and
it is kept anyway because **production does the same** and dev is meant to be the release gate,
not a third convention.

It is also cheaper than the alternative in a specific way: `delete_inactive_users.kt` hardcodes
`realms/master` in `getAccessToken()` while using the configured `$realm` for the admin calls. Those
two disagree the moment the realm is not `master`, so moving to a dedicated realm is a code change,
not a config change. **That half is now done** (`10f169c1`), so what remains is the realm itself.

#### What it costs, concretely

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

Registration off is the decision from `doc/dev-environment.md` §7: accounts are admin-created,
which removes the "anyone with a UT account wanders into dev" problem entirely.

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

**`view-users` and never `manage-users`**, from `doc/dev-environment.md` §5. The reason is
specific rather than general caution: core's `DeleteInactiveUsers` cron deletes accounts from the
database *and* from Keycloak, and dev's imported `last_seen` values are historical, so an unpinned
run would delete a large slice of the import from both. The cron is pinned to the never-date as the
first defence. This is the second, and it is the one that still holds when someone unpins the cron
without reading why it was pinned.

Verify it, rather than assuming:

```sh
$K get users/$SA/role-mappings/clients/$($K get clients -r master -q clientId=master-realm \
  --fields id --format csv --noquotes | head -1) -r master --fields name --format csv --noquotes
# expect exactly: view-users
```

### 4.5 The theme

`lahendus` is installed from `~kspar/easy-kc-theme` into `/opt/keycloak/themes/`, as a directory
rather than a jar so it can be edited and reloaded without a build step.

It was written for Keycloak 15, and on 25 it asked for three stylesheets the Quarkus distributions do
not ship — two PatternFly 4 paths under a `web_modules/` directory that no longer exists, and a
`css/tile.css` that is in neither this theme nor its parent. A 404 stylesheet is an unstyled page
rather than a visible error, so the role used to rewrite `styles` and `stylesCommon` on the way in.

Those rewrites are gone. Upstream `easy-kc-theme` is now tested against current Keycloak and ships
the right paths, and a role that patches its source would hide the next such break instead of
surfacing it. **If the login page comes up unstyled after a version bump, fix it in
github.com/kspar/easy-kc-theme** — check the browser's network tab for 404s under
`/auth/resources/`, then compare against the stock `keycloak/login/theme.properties` of the version
you are on.

**The theme still has no home here, and that is the remaining problem.** The role copies it from a
directory on the host, so a rebuilt host gets whatever that directory happens to hold. The copy
happens only if it is not already installed, so to pick up an edited source: remove
`/opt/keycloak/themes/lahendus` and re-run. **Vendoring it into this repo is outstanding work.**

To fall back to the stock login page, no playbook run needed:

```sh
$K update realms/master -s loginTheme=keycloak
```

### 4.6 The admin-console gate

`https://dev.idp.lahendus.ut.ee/idp-admin/` is a page of ours that stands in front of the admin
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
HOST=dev.idp.lahendus.ut.ee
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
in the imported data — `doc/dev-environment.md` §3.4. That tester then inhabits that (anonymised)
teacher's courses, which is the deliberate, auditable way to get realistic access.

---

### 4.8 The smoke client and its two accounts

The unattended deployer (`roles/core_rollout`, `doc/production-rollout.md`) proves an environment
works by logging in as a student and as a teacher and doing what they do — without a browser. §4.3
turned the password grant off on the SPA's client on purpose, and §6.3 turns it on for one request
and back off again; a suite that runs twice per rollout cannot do that. So it gets a client of its
own, confidential, with the password grant and nothing else:

```sh
$K create clients -r master \
  -s clientId=easy-smoke -s enabled=true \
  -s publicClient=false -s standardFlowEnabled=false -s implicitFlowEnabled=false \
  -s directAccessGrantsEnabled=true -s serviceAccountsEnabled=false
SMOKE=$($K get clients -r master -q clientId=easy-smoke --fields id --format csv --noquotes | head -1)

# core reads `easy_role` and checks no audience (EasyUserJwtConverter.kt), so a token from this
# client works exactly like one from the SPA as long as it carries the same claim — the SPA
# client's roles, mapped by the same mapper.
$K create clients/$SMOKE/protocol-mappers/models -r master \
  -s name=easy_role -s protocol=openid-connect \
  -s protocolMapper=oidc-usermodel-client-role-mapper \
  -s 'config."usermodel.clientRoleMapping.clientId"=lahendus.ut.ee' \
  -s 'config."claim.name"=easy_role' -s 'config."jsonType.label"=String' \
  -s 'config."multivalued"=true' -s 'config."access.token.claim"=true' \
  -s 'config."id.token.claim"=false' -s 'config."userinfo.token.claim"=true'

$K get clients/$SMOKE/client-secret -r master --fields value --format csv --noquotes   # → smoke-secrets.json
```

Two accounts, created like any other (§4.7) with the SPA client's `student` and `teacher` roles
respectively, long random passwords, and email addresses that exist somewhere harmless — core
requires the `email` claim:

```sh
for who in student teacher; do
  pw=$(openssl rand -base64 30)
  $K create users -r master -s username=easy-smoke-$who -s enabled=true -s emailVerified=true \
    -s email=easy-smoke-$who@example.invalid -s firstName=Smoke -s lastName=${who^}
  $K set-password -r master --username easy-smoke-$who --new-password "$pw"
  $K add-roles -r master --uusername easy-smoke-$who --cclientid lahendus.ut.ee --rolename $who
  echo "easy-smoke-$who: $pw"      # goes into /etc/easy/smoke-secrets.json, then nowhere else
done
```

The names are what the suite's `checkin` posts (`Smoke` / `Student`, `Smoke` / `Teacher`), so
core has nothing to update on every run. The addresses need not deliver — the two accounts get no
mail that matters — but they must be set, because core requires the `email` claim.

Or, having created the client and the two users in the console, let `ansible/smoke-idp-setup.yml`
do the mapper, the roles and the names — idempotently, over kcadm on the host. In the console the
mapper is: Clients → `easy-smoke` → Client scopes → `easy-smoke-dedicated` → Add mapper → By
configuration → **User Client Role**, with Client ID `lahendus.ut.ee` (where the roles live, not
`easy-smoke`), Token Claim Name `easy_role`, Multivalued on, JSON type String, in the access token.

What the client can do is bounded by what those two accounts can do: a student in one course and
a teacher of that same course, both with nothing else — and in particular neither is an admin,
which is why the smoke course itself is created by one (doc/production-rollout.md §9). The secret
and the two passwords go into `/etc/easy/smoke-secrets.json` on the core host and nowhere else.
Rotate all three by hand once a year; `easy-smoke` on the host says immediately if a rotation was
incomplete.

## 5. The hostname, which went round in a circle

Every config in this repo pointed the IdP at **`dev.idp.lahendus.ut.ee`**, and on 2026-08-08 that
name was a CNAME to `proxy.hpc.ut.ee` (193.40.46.68/69) — a host that had never served this IdP. It
could not have worked, so everything was moved to **`easy-idp-dev.cloud.ut.ee`**, the VM's own name,
which resolves to 193.40.11.153.

On **2026-08-21** the alias was repointed at the VM, and everything moved back:

```
dev.idp.lahendus.ut.ee.  ->  easy-idp-dev.cloud.ut.ee.  ->  193.40.11.153
```

Both names reach the VM, so this is a choice rather than a fix. The `lahendus.ut.ee` name wins
because it is ours: it can follow the IdP to another VM, where `easy-idp-dev.cloud.ut.ee` is
whatever OpenStack called the machine we happen to be running on today. Tokens minted on dev now say
`https://dev.idp.lahendus.ut.ee/auth/realms/master`.

This matters more than a rename usually does, because Keycloak's `hostname` decides the `issuer`
claim in every token, and core rejects any token whose issuer is not the configured one. The role
asserts the two agree after every run.

Set in: `ansible/inventories/dev/group_vars/idp.yml` (`keycloak_hostname`, which is also the
certificate's name), `ansible/inventories/dev/group_vars/all/core.yml`
(`easy_core_idp_base_url`), and `deploy/dev/config.json` (`keycloak.url` and `idpAdminUrl`, which a
web deploy copies onto the core host).

`web/public/config.json` is **unchanged** — it holds production's values, not dev's.

### 5.1 Applying a hostname change

Three things break in three different ways if this is done piecemeal, so the order is the point.

1. **The realm's gate client first**, over kcadm on the host. `idp-admin-gate` has the IdP's own
   hostname in `rootUrl`, `redirectUris` and `webOrigins` — realm data, so no playbook touches it —
   and Keycloak refuses a redirect URI it does not recognise. Both names at once, so the page works
   either side of the switch:

   ```sh
   HOST=dev.idp.lahendus.ut.ee
   OLD=easy-idp-dev.cloud.ut.ee
   ID=$($K get clients -r master -q clientId=idp-admin-gate --fields id --format csv --noquotes)
   $K update clients/$ID -r master \
     -s rootUrl="https://$HOST" \
     -s "redirectUris=[\"https://$HOST/idp-admin/*\",\"https://$OLD/idp-admin/*\"]" \
     -s "webOrigins=[\"https://$HOST\",\"https://$OLD\"]"
   ```

   The SPA client `lahendus.ut.ee` needs nothing: its redirect URIs are `dev.lahendus.ut.ee`, the
   web origin, which does not change when the IdP is renamed.

2. **Then the playbook**, which does Keycloak and core in one run and asserts they agree:

   ```sh
   cd ansible && ./run.sh site.yml --limit easyidpdev,easycoredev
   ```

   Expect a **short HTTPS gap on the IdP**, by design: the vhost is written without its TLS block
   while no certificate exists for the new name, certbot then obtains one over HTTP-01 (port 80 is
   already answering on the new name), and the second write puts TLS back. Logins fail for that
   minute. If certbot fails the IdP stays HTTP-only until it is fixed, so this is a thing to watch
   rather than start and walk away from.

   Everyone is signed out regardless: tokens carrying the old issuer are rejected by the newly
   configured core, which is correct and looks exactly like an outage to anyone mid-session.

3. **Then a deploy, promptly**, because step 2 does not finish the job. On an autodeploying host —
   dev is one, production is not — `core_autodeploy` writes `/srv/easy/conf/config.json`, but the
   copy a browser loads is `web/config.json` inside the *current release*, which only gets it when a
   release is placed. Where autodeploy is off there is no `conf/config.json` at all and `deploy.sh`
   is the only thing that places one, which makes this step not merely prompt but mandatory. So between step 2 and this, the
   SPA is still sending people to the old name — which by then has no certificate that matches, so
   they get a TLS warning rather than a login page.

   ```sh
   SSH_TARGET=easycoredev ./deploy/deploy.sh dev latest
   ```

   Any release placement does it, which is a more useful way to think about it than "a deploy":
   `easy-autodeploy.py` copies `conf/config.json` into every release it installs, rollbacks included,
   because that file belongs to the environment and not to the release. Two consequences. The
   autodeploy timer will *not* do this for you while `dev-releases` sits still — it only acts when the
   branch moves — so waiting is not a plan. And it does not matter that the timer then reverts the
   release you just placed (it will, see `deploy/README.md`): the config.json it copies in is the
   environment's either way, so the new IdP URL survives being rolled back onto an older build.

Then check it, rather than assuming: a token from the new IdP should be accepted by core, and a
tampered copy of that same token should not — otherwise "accepted" only tells you verification is
switched off. `doc/core/api-testing.md` has the client-credentials recipe.

Afterwards, the old certificate keeps renewing for a name nothing serves, and the gate client still
lists it. Once the new name has been seen working: `sudo certbot delete --cert-name <old>` on the IdP
host, and re-run the step 1 command with only the new name in it.

**One wrinkle if dev is behind master.** Step 2's `--limit easycoredev` runs the whole `Core` play, so
any other role with pending changes applies too — `./run.sh site.yml --check --diff --limit easycoredev`
first, and if it names roles you did not intend to touch, run a copy of site.yml's `Core` play with
those roles removed instead. It must live inside `ansible/`: `core_autodeploy` resolves this
environment's `config.json` relative to `playbook_dir`, so a playbook kept outside the repo fails on
that task with `Could not find or access .../deploy/dev/config.json`.

---

## 6. Pointing core at it

Three things, of which the second is a bug that had been live and invisible.

### 6.1 The base URL is the origin only

```yaml
easy_core_idp_base_url: https://dev.idp.lahendus.ut.ee    # NO path
easy_core_idp_path_prefix: /auth                          # added by the template
easy_core_keycloak_realm: master
easy_core_keycloak_client_id: easy-core
```

`easy_core_idp_base_url` used to have the `/auth` on the end of it, and one variable was doing two
incompatible jobs:

- `jwk-set-uri` / `issuer-uri` are built as `{base}/realms/...` and **need** the `/auth` prefix.
- `easy.core.keycloak.base-url` is consumed by `delete_inactive_users.kt`, which appends `/auth`
  **itself** — in all three of `getAccessToken`, `getKeycloakUserId` and `deleteKeycloakUser`.

So the old value made JWKS correct and every admin-API call `/auth/auth/...`, which 404s. Nobody saw
it because dev pins the cron that drives those calls to a date that never comes, so the broken URL
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
curl -s https://dev.idp.lahendus.ut.ee/auth/realms/master/.well-known/openid-configuration | jq .issuer
ssh easyidpdev 'systemctl is-active keycloak; curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:9000/auth/health/ready'
```

For admin work go to <https://dev.idp.lahendus.ut.ee/idp-admin/>, which checks whether your account
may use the console and sends you there if it may. The console's own URL,
<https://dev.idp.lahendus.ut.ee/auth/admin/>, works too — but only if you are already an
administrator, and gives no clue at all if you are not (§4.6).

---

## 8. Still to do

- **Vendor the theme** — EZ-1744 (Vendor the lahendus Keycloak theme — it exists only in a home
  directory on one VM). The stylesheet half of this is done: upstream fixed the paths, and the two
  `lineinfile` tasks that used to correct them after the copy are gone (§4.5). What remains is that
  the source is a directory on a VM rather than something this repo carries.
- **Back up `cloakdb` and `/etc/keycloak/keycloak.env`** — EZ-1745 (Back up the IdP: cloakdb and
  /etc/keycloak/keycloak.env are both single copies). This document reproduces the realm; it does not
  reproduce the users in it, so today the IdP is rebuildable and logins are not (§2).
- **A dedicated realm instead of `master`** (§4.1). The `getAccessToken()` half is **done** — all
  three admin URLs now follow `easy.core.keycloak.realm` — so what remains is the realm itself, the
  config in three places, and production doing the same so the two do not diverge.

  Two things get **deleted** rather than migrated when that happens, and both will look like working
  code at the time: the `/idp-admin/` gate and its client (§4.6), which guard a door application
  users would no longer be able to reach, and the paragraph in §4.1 explaining why they exist. The
  ledger for that move is longer than the realm itself, which is the honest reason it keeps not
  happening.
- ~~**Production.** The production IdP has never been touched by this role.~~ **Out of date as of
  2026-09-03** — checked, and it has been. Whatever remains to say about that host belongs where the
  rest of production's detail lives, not in this repo.

  What is worth writing down here, because it is about the role rather than about any host: **a
  vhost-only apply is possible.** A throwaway playbook whose tasks are two `include_role` calls with
  `tasks_from: legacy_adapter.yml` and `tasks_from: nginx.yml` changes the adapter file and the vhost
  and nothing else. Going through `site.yml` instead would also run `install.yml` and `service.yml`,
  which own the pinned version and `kc.sh build` — a server restart, and possibly a version move, in
  service of a config file. `nginx.yml` brings its own `nginx -t` and its own rollback, so a bad
  template is refused rather than reloaded.

Not this document's, but found here and affecting this host: EZ-1746 (A reboot-required left by
manual apt is never acted on, so hosts run indefinitely on superseded kernels).
