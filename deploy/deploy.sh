#!/usr/bin/env bash
#
# Deploy a CI-built Lahendus release to an environment.
#
#   deploy/deploy.sh dev latest               # newest green CI run on that env's branch
#   deploy/deploy.sh dev 1a2b3c4              # a specific commit, full or short sha
#   deploy/deploy.sh dev 1a2b3c4 --dry-run    # resolve and download, touch nothing remote
#   deploy/deploy.sh prod 1a2b3c4             # prompts, dumps the database, then deploys
#
# The environment is the first argument and there is no default, for the same reason `ansible/` has
# no default inventory: an omitted environment should be a usage error at the command line, not a
# deploy to whichever one happened to be hardcoded. Everything environment-specific lives in
# deploy/<env>/<env>.env beside this script.
#
# Needs gh (authenticated), jq, and SSH to the host. Deliberately no JDK and no Node: the artifacts
# come from the CI run that gated the commit, so what dev exercises is byte-for-byte what goes to
# production. SSH access is therefore the real deploy permission.
# See doc/dev-environment.md §8 for dev and doc/production-update.md for production.
#
# Rollback is this same command with an older sha. Releases stay on the host, so rolling back to
# one still in $REMOTE_ROOT/releases needs neither a download nor a surviving CI run — which
# matters, since GitHub expires artifacts after 90 days. A rollback across a Liquibase migration
# does NOT roll the schema back; that needs the nightly dump (§3.5).

set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

die() { echo "error: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }

usage() {
    sed -n '3,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit "${1:-1}"
}


# The environment comes first and is consumed before the option loop, so `deploy.sh --dry-run dev x`
# is a usage error rather than a deploy of an environment called `--dry-run`.
[ $# -ge 1 ] || usage 1
case "${1:-}" in -h|--help) usage 0 ;; -*) die "the first argument is the environment, got '$1'" ;; esac
readonly ENV_NAME="$1"; shift
readonly ENV_DIR="$SCRIPT_DIR/$ENV_NAME"

[ -d "$ENV_DIR" ] || die "no such environment '$ENV_NAME' — expected $ENV_DIR ($(cd "$SCRIPT_DIR" && ls -d */ 2>/dev/null | tr -d / | paste -sd' ' -))"
[ -f "$ENV_DIR/$ENV_NAME.env" ] || die "missing $ENV_DIR/$ENV_NAME.env"

# shellcheck source=dev/dev.env
source "$ENV_DIR/$ENV_NAME.env"

# Defaults for the settings only some environments set, so an env file that predates them still
# works and the safe value is the one you get by saying nothing.
: "${PRE_RESTART_DUMP:=false}"
: "${REQUIRE_CONFIRM:=false}"
: "${DUMP_SERVICE:=easy-db-backup.service}"

: "${GH_REPO:=kspar/easy}"
export GH_REPO

readonly WORKFLOW="CI"
readonly HEALTH_TIMEOUT_S=120

# --- arguments -------------------------------------------------------------------------------

REF=""
DRY_RUN=false
ASSUME_YES=false
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) usage 0 ;;
        --dry-run) DRY_RUN=true ;;
        --yes|-y) ASSUME_YES=true ;;
        -*) die "unknown option: $1" ;;
        *) [ -z "$REF" ] || die "expected one sha, got '$REF' and '$1'"; REF="$1" ;;
    esac
    shift
done
[ -n "$REF" ] || usage 1

# `latest` resolves to whatever the branch points at now, which is fine for an environment that
# redeploys several times a day and wrong for one that is deployed deliberately. An environment
# asking for confirmation is saying its deploys are named, so it does not get to guess.
if [ "$REQUIRE_CONFIRM" = true ] && [ "$REF" = "latest" ]; then
    die "'$ENV_NAME' wants an explicit sha, not 'latest' — name the commit you mean"
fi

# --- preflight -------------------------------------------------------------------------------

step "Preflight"

for cmd in gh jq ssh scp curl tar; do
    command -v "$cmd" >/dev/null || die "$cmd is not installed"
done
gh auth status >/dev/null 2>&1 || die "gh is not authenticated — run 'gh auth login'"

[ -f "$ENV_DIR/config.json" ] || die "missing $ENV_DIR/config.json"

# A dry run resolves and downloads and stops there, so it must work with no host at all — that is
# what makes it useful before the dev VM exists.
if [ "$DRY_RUN" = false ]; then
    # Env files ship with a placeholder rather than a wrong hostname that looks right.
    case "$SSH_TARGET" in
        ""|*TODO*) die "SSH_TARGET is still a placeholder — set it in $ENV_DIR/$ENV_NAME.env" ;;
    esac

    ssh -o BatchMode=yes -o ConnectTimeout=10 "$SSH_TARGET" true \
        || die "cannot SSH to $SSH_TARGET (needs key-based auth, no password prompt)"
fi

# --- resolve the commit to a green CI run ------------------------------------------------------

step "Resolving $REF"

# `gh run list --commit` matches full shas only, so the filtering happens in jq: a short sha is
# what anyone actually types, and `git rev-parse` cannot help for a commit the laptop hasn't
# fetched. 100 runs is a few weeks of master.
runs="$(gh run list --workflow "$WORKFLOW" --limit 100 \
    --json databaseId,headSha,headBranch,status,conclusion,url,createdAt)"

if [ "$REF" = "latest" ]; then
    run="$(jq -c --arg branch "$DEPLOY_BRANCH" '
        [ .[] | select(.headBranch == $branch and .status == "completed" and .conclusion == "success") ]
        | sort_by(.createdAt) | last // empty' <<<"$runs")"
    [ -n "$run" ] || die "no green $WORKFLOW run found on $DEPLOY_BRANCH in the last 100 runs"
else
    # The newest GREEN run for this commit — green first, newest second, and that order is the
    # whole point.
    #
    # This used to take the newest run outright and gate on its conclusion afterwards, which meant
    # any run created after a commit was validated got to overrule the ones that passed. A
    # promotion push rebuilding the same tree on a release branch is enough. It happened to
    # `929718ea` while it was live in production: green on master and on dev-releases, then a third
    # run of the identical tree flaked in the browser suite, and the rollback target became
    # undeployable — at exactly the moment nobody wants to be told to go and re-run Playwright.
    #
    # Filtering first keeps what "newest wins" was for — re-run a flake and the re-run counts — and
    # drops only the case nobody wants. It is not a loosening: CI on a fixed tree is deterministic
    # apart from flake and infrastructure, so a later red run against identical source says nothing
    # new about the code. `latest` above has always worked this way, and so does
    # easy-autodeploy.py, which is why dev never met this.
    run="$(jq -c --arg sha "$REF" '
        [ .[] | select((.headSha | startswith($sha))
                       and .status == "completed" and .conclusion == "success") ]
        | sort_by(.createdAt) | last // empty' <<<"$runs")"

    if [ -z "$run" ]; then
        # Say which runs exist and how they ended, so "wait" and "re-run it" are distinguishable
        # without a trip to the Actions tab. The old message named one run and left the rest unsaid.
        seen="$(jq -r --arg sha "$REF" '
            [ .[] | select(.headSha | startswith($sha)) ]
            | sort_by(.createdAt)
            | if length == 0 then "  (no runs at all for that commit)"
              else map("  \(.headBranch)  \(.status)/\(.conclusion // "-")  \(.url)") | join("\n")
              end' <<<"$runs")"
        die "no green $WORKFLOW run for '$REF' in the last 100 runs. Runs for it:
$seen"
    fi
fi

SHA="$(jq -r .headSha <<<"$run")"
RUN_ID="$(jq -r .databaseId <<<"$run")"
RUN_URL="$(jq -r .url <<<"$run")"
RUN_STATUS="$(jq -r .status <<<"$run")"
RUN_CONCLUSION="$(jq -r .conclusion <<<"$run")"
RUN_BRANCH="$(jq -r .headBranch <<<"$run")"

echo "  commit  $SHA ($RUN_BRANCH)"
echo "  run     $RUN_URL"

# The gate. Artifacts are published per-job, so a jar can exist for a run whose web job failed —
# only the run's own conclusion says the whole thing was green.
#
# Both selectors above now filter on this themselves, so in normal operation neither line can fire.
# Kept deliberately rather than deleted: the property "we only ever install a run that concluded
# success" is the one thing here that must not be lost to an edit of a jq expression, and it costs
# two lines to state it where it is enforced instead of trusting a filter twenty lines up.
[ "$RUN_STATUS" = "completed" ] \
    || die "run is '$RUN_STATUS', not finished yet — $RUN_URL"
[ "$RUN_CONCLUSION" = "success" ] \
    || die "run concluded '$RUN_CONCLUSION', refusing to deploy it — $RUN_URL"

# --- does the host already have this release? --------------------------------------------------

REMOTE_RELEASE="$REMOTE_ROOT/releases/$SHA"

if [ "$DRY_RUN" = true ]; then
    NEED_UPLOAD=true
elif ssh "$SSH_TARGET" "test -f '$REMOTE_RELEASE/core.jar' && test -d '$REMOTE_RELEASE/web'"; then
    echo "  host already has this release, skipping download and upload (rollback path)"
    NEED_UPLOAD=false
else
    NEED_UPLOAD=true
fi

# --- fetch artifacts ---------------------------------------------------------------------------

if [ "$NEED_UPLOAD" = true ]; then
    step "Downloading artifacts from run $RUN_ID"

    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"' EXIT

    gh run download "$RUN_ID" -n "core-$SHA" -n "web-$SHA" -D "$TMP" \
        || die "artifacts missing from $RUN_URL — only master and release branches publish them, and they expire after 90 days"

    # Globbed rather than assumed: whether gh lands a single-file artifact at the root or inside a
    # directory named after it has changed between versions, and either layout is fine here.
    jar="$(find "$TMP" -name "core-$SHA.jar" -type f -print -quit)"
    tgz="$(find "$TMP" -name "web-$SHA.tar.gz" -type f -print -quit)"
    [ -n "$jar" ] || die "core-$SHA.jar not found in the downloaded artifact"
    [ -n "$tgz" ] || die "web-$SHA.tar.gz not found in the downloaded artifact"

    echo "  core  $(du -h "$jar" | cut -f1)"
    echo "  web   $(du -h "$tgz" | cut -f1)"
fi

if [ "$DRY_RUN" = true ]; then
    step "Dry run — stopping before touching $SSH_TARGET"
    exit 0
fi

# --- confirm -----------------------------------------------------------------------------------

# For environments where a deploy is an event rather than a habit. Everything above this line is
# read-only, so this is the last point at which stopping costs nothing.
if [ "$REQUIRE_CONFIRM" = true ] && [ "$ASSUME_YES" = false ]; then
    step "Confirm"
    cat <<CONFIRM
  environment  $ENV_NAME
  commit       $SHA ($RUN_BRANCH)
  run          $RUN_URL
  target       $SSH_TARGET
  service      $CORE_SERVICE
$([ "$PRE_RESTART_DUMP" = true ] && echo "  database     dumped via $DUMP_SERVICE before the restart")

  Liquibase applies every pending changeset when core starts, and a rollback of the jar does NOT
  roll the schema back.
CONFIRM
    printf '  Type the environment name to continue: '
    read -r reply
    [ "$reply" = "$ENV_NAME" ] || die "not confirmed (got '$reply'), nothing was changed"
fi

# --- upload --------------------------------------------------------------------------------

if [ "$NEED_UPLOAD" = true ]; then
    step "Uploading to $SSH_TARGET:$REMOTE_RELEASE"
    ssh "$SSH_TARGET" "mkdir -p '$REMOTE_RELEASE'"
    # Names are normalised on the way over; the sha is already in the directory name.
    scp -q "$jar" "$SSH_TARGET:$REMOTE_RELEASE/core.jar"
    scp -q "$tgz" "$SSH_TARGET:$REMOTE_RELEASE/web.tar.gz"
fi

# Re-copied on every deploy, including rollbacks: this file is the environment's, not the
# release's, so a rollback should keep today's config rather than resurrect the old one.
scp -q "$ENV_DIR/config.json" "$SSH_TARGET:$REMOTE_RELEASE/config.json"

# --- install and restart -----------------------------------------------------------------------

step "Installing and restarting $CORE_SERVICE"

ssh "$SSH_TARGET" bash -s -- \
    "$SHA" "$REMOTE_ROOT" "$CORE_SERVICE" "$KEEP_RELEASES" "$PRE_RESTART_DUMP" "$DUMP_SERVICE" <<'REMOTE'
set -euo pipefail
sha="$1"; root="$2"; service="$3"; keep="$4"; dump="$5"; dump_service="$6"
rel="$root/releases/$sha"

# The release tree is setgid for the deploy group, so a release this person installs is one the
# host's own deployer (roles/core_rollout) may later have to roll back to — which means rewriting
# its config.json. Group-writable from the start, or that rollback fails on a permission error.
umask 002

# Core reads this through --spring.config.location and dies on an unresolved @Value placeholder,
# so a release that adds a config key takes the environment down on restart. That is by design —
# dev is where that gets caught instead of production (§8.4) — but a missing file entirely is
# a first-deploy mistake worth naming before the service goes down for it.
[ -f "$root/conf/application.yaml" ] \
    || { echo "error: $root/conf/application.yaml does not exist" >&2; exit 1; }

# Unpack the dist. Fresh directory each time so a file deleted between releases does not linger.
rm -rf "$rel/web"
mkdir -p "$rel/web"
tar -xzf "$rel/web.tar.gz" -C "$rel/web"
mv "$rel/config.json" "$rel/web/config.json"

# CI strips config.json from the dist, so without this the app would render its "Configuration
# error" page. Verified rather than assumed: a truncated scp is otherwise found by a tester.
python3 - "$rel/web/config.json" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    c = json.load(f)
missing = [k for k in ("emsRoot",) if not c.get(k)]
missing += ["keycloak." + k for k in ("url", "realm", "clientId")
            if not c.get("keycloak", {}).get(k)]
if missing:
    sys.exit("config.json is missing: " + ", ".join(missing))
print("  config.json -> %s, realm %s at %s" % (
    c["emsRoot"], c["keycloak"]["realm"], c["keycloak"]["url"]))
PY

[ -f "$rel/web/index.html" ] || { echo "error: no index.html in the unpacked dist" >&2; exit 1; }

# Flip both symlinks. `ln -sfn` on an existing symlink is not atomic — it unlinks first, and a
# request landing in that window gets a 404 — so build the new link beside it and rename over.
mkdir -p "$root/web" "$root/core"
ln -sfn "$rel/web" "$root/web/.current.new"
mv -Tf "$root/web/.current.new" "$root/web/current"
ln -sfn "$rel/core.jar" "$root/core/.current.new"
mv -Tf "$root/core/.current.new" "$root/core/current.jar"

# The restore point, taken as late as possible: after every check that can fail has passed, and
# before the restart that runs the migrations. Reusing the nightly backup unit rather than a
# pg_dump of its own means one dump implementation, one retention policy, one place where the
# "did it actually finish" check lives — and one exact sudoers line instead of a grant for pg_dump
# with arguments. It is a oneshot, so this blocks until the dump is written and verified, which on
# a large database is minutes rather than seconds.
if [ "$dump" = true ]; then
    echo "  dumping the database via $dump_service before restarting"
    sudo systemctl start "$dump_service"
fi

sudo systemctl restart "$service"
date -Iseconds > "$rel/DEPLOYED"
echo "$sha" > "$root/current-sha"

# Prune old releases, newest first, never the one just deployed.
n=0
for d in $(ls -1dt "$root"/releases/*/ 2>/dev/null); do
    n=$((n + 1))
    [ "$n" -le "$keep" ] && continue
    [ "$(basename "$d")" = "$sha" ] && continue
    echo "  pruning $(basename "$d")"
    rm -rf "$d"
done
REMOTE

# --- wait for core -------------------------------------------------------------------------

step "Waiting for core"

# 401 is the success condition, not a failure. Core has no unauthenticated health endpoint — the
# only permitAll() routes are the two anonymous-autoassess ones, which need a real exercise id —
# and Spring Security answers everything else with 401 before routing. So a 401 through the public
# vhost proves the whole chain: Apache is proxying, core is up, and its filter chain is built.
# It relies on the API vhost being the dumb proxy §4.2 describes; an Apache that authenticated
# would return 401 by itself and this check would pass over a dead core. `systemctl is-active`
# below is what covers that.
deadline=$((SECONDS + HEALTH_TIMEOUT_S))
code=""
while [ "$SECONDS" -lt "$deadline" ]; do
    code="$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$HEALTH_URL" || true)"
    case "$code" in
        401|200) break ;;
    esac
    sleep 2
    code=""
done

if [ -z "$code" ]; then
    echo "  no answer from $HEALTH_URL after ${HEALTH_TIMEOUT_S}s — last 40 log lines:" >&2
    # The sudoers grant for this is an *exact* command match (ansible/roles/core_service/templates/
    # sudoers-deploy.j2), so changing these arguments — `-n 40`, `--no-pager` — silently stops it
    # matching. It fails into a password prompt nobody can answer, and `|| true` swallows that, so
    # the symptom is a failed deploy printing no logs at all. Change both together.
    ssh "$SSH_TARGET" "sudo journalctl -u '$CORE_SERVICE' -n 40 --no-pager" >&2 || true
    die "deploy finished but core is not answering"
fi

ssh "$SSH_TARGET" "systemctl is-active --quiet '$CORE_SERVICE'" \
    || die "$HEALTH_URL answered $code but $CORE_SERVICE is not active — is something else on that vhost?"

# Read back rather than echoing what we set, so this reports the host's state and not our intent.
step "Deployed $(ssh "$SSH_TARGET" "cat '$REMOTE_ROOT/current-sha'")"
echo "  web   $WEB_URL"
echo "  api   $HEALTH_URL"
echo "  from  $RUN_URL"
