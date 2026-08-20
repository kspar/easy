#!/usr/bin/env bash
#
# Build, verify and publish the grading images.
#
# Called by .github/workflows/grading-images.yml, and runnable by hand on a laptop — the default is
# to build and check without publishing anything, so `bin/build-grading-images.sh` is a safe thing to
# type while working on a Dockerfile.
#
#   REGISTRY=ghcr.io/kspar/easy   where published images go
#   ENVIRONMENTS="dev prod"       whose pins to build; each gets a channel tag of its own name
#   PUSH=false                    publish, and move the channel tags
#   FORCE=false                   rebuild even a digest that is already published
#   IMAGES=""                     a subset to build, in place of every image in dependency order
#
# ## The three things this does that matter
#
# **It never rebuilds a digest it has already published.** `bin/pins.py digest` is a hash of
# everything that decides an image's contents — its Dockerfile, its smoke script, its pins, and
# recursively its base's digest — so an existing `:i<digest>` tag is already the image this build
# would produce. Skipping it is what makes a published tag genuinely immutable rather than immutable
# by convention, and it is why reverting a pin is fast: the artefact is still there, so the rollback
# build is a pull.
#
# **It refuses to publish an image that lies about itself.** Every image carries `/easy-smoke.sh`,
# which asserts that what pip actually installed is what the pins declared, and then exercises the
# thing that image exists for. An image that fails it is not pushed and nothing downstream of it is
# built. That check is the reason the labels below can be trusted.
#
# **It records what is installed, not what was asked for.** The declared versions are what somebody
# wrote down; the installed ones are read back out of the finished image. On 2026-08-20 those two
# answers differed on dev for a fortnight and nothing could see it, because nothing had ever put them
# side by side. Now every image carries both.
set -euo pipefail

REGISTRY="${REGISTRY:-ghcr.io/kspar/easy}"
ENVIRONMENTS="${ENVIRONMENTS:-dev prod}"
PUSH="${PUSH:-false}"
FORCE="${FORCE:-false}"
IMAGES="${IMAGES:-}"

# Digests built during this run, so a second environment pinning the same versions reuses the
# artefact instead of rebuilding it.
BUILT_DIGESTS=""

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PINS="$REPO_ROOT/bin/pins.py"
CONTEXT="$REPO_ROOT/doc/aae/dockerfiles"

say() { printf '\n=== %s\n' "$*"; }

# Whether the registry already holds this exact image. `docker manifest inspect` asks without
# pulling, and a failure here means "not published" rather than "broken": on a pull request there are
# no registry credentials at all, so every image is treated as unpublished and simply gets built.
is_published() {
    docker manifest inspect "$1" >/dev/null 2>&1
}

# The installed versions, read out of the image that was just built.
#
# `--network none` because nothing here needs the network and this runs on hosts that grade untrusted
# code; `--entrypoint python3` because these images set no entrypoint of their own and relying on
# their CMD would break the moment one gained a different default.
installed_versions() {
    local image="$1" packages="$2"
    docker run --rm --network none -e "EASY_REPORT=$packages" --entrypoint python3 "$image" -c '
import importlib.metadata as meta, os
out = []
for name in os.environ["EASY_REPORT"].split():
    try:
        out.append(f"{name}=={meta.version(name)}")
    except meta.PackageNotFoundError:
        # Reported rather than skipped: an absent package is a fact about the image, and a label
        # that quietly omitted it would read as "nothing to say" instead of "this is wrong".
        out.append(f"{name}==absent")
print(" ".join(out))
'
}

build_one() {
    local env="$1" image="$2"
    local digest pinned bare declared expect packages installed staged

    digest="$("$PINS" digest --env "$env" "$image")"
    pinned="$REGISTRY/$image:i$digest"
    bare="$image"

    # Two environments pinning the same versions resolve to the same digest — which is the normal
    # state, since production is meant to run what dev has proved. Building it twice would be pure
    # waste, and on a pull request (where there is no registry to ask) the published-tag check cannot
    # notice. The channel still has to move for the second environment, so this skips the build and
    # not the publish.
    if [[ " $BUILT_DIGESTS " == *" $digest "* ]]; then
        say "$env/$image: i$digest was already built in this run"
        publish "$env" "$image" "$pinned"
        return
    fi

    if [[ "$FORCE" != "true" ]] && is_published "$pinned"; then
        say "$env/$image: i$digest is already published — pulling it instead of rebuilding"
        docker pull -q "$pinned"
        # The bare tag is what a child's `FROM` resolves to, and what a host grades with. It has to
        # exist locally even when nothing was built.
        docker tag "$pinned" "$bare"
        publish "$env" "$image" "$pinned"
        return
    fi

    declared="$("$PINS" declared --env "$env" "$image")"
    packages="$("$PINS" packages --env "$env" "$image" | tr '\n' ' ')"

    say "$env/$image: building i$digest ($declared)"

    local args=()
    while IFS='=' read -r key value; do
        [[ -n "$key" ]] && args+=(--build-arg "$key=$value")
    done < <("$PINS" args --env "$env" "$image")

    staged="easy-grading-staging/$image:i$digest"

    # The GitHub Actions cache only exists inside a workflow, and asking for it anywhere else fails
    # the build rather than degrading. So it is opt-in: CI sets CACHE=gha, a laptop sets nothing and
    # relies on the local layer cache.
    local cache=()
    if [[ "${CACHE:-}" == "gha" ]]; then
        cache+=(--cache-from "type=gha,scope=$image" --cache-to "type=gha,scope=$image,mode=max")
    fi

    docker buildx build \
        --load \
        --file "$CONTEXT/$image" \
        --tag "$staged" \
        ${args[@]+"${args[@]}"} \
        ${cache[@]+"${cache[@]}"} \
        "$CONTEXT"

    say "$env/$image: verifying i$digest before it is given a published name"
    local smoke_env=()
    while IFS= read -r line; do
        [[ -n "$line" ]] && smoke_env+=(-e "$line")
    done < <("$PINS" expect-env --env "$env" "$image")
    docker run --rm --network none --memory 768m ${smoke_env[@]+"${smoke_env[@]}"} "$staged" /easy-smoke.sh

    installed="$(installed_versions "$staged" "$packages")"
    say "$env/$image: installed $installed"

    # The labels have to be applied *after* the smoke test, because one of them is a fact only the
    # finished image can answer. A one-line image FROM the staged one is the cheapest way to add
    # them: it shares every layer and adds only metadata.
    docker build -q -t "$pinned" -t "$bare" - <<EOF
FROM $staged
LABEL easy.grading.inputs="$digest"
LABEL easy.grading.declared="$declared"
LABEL easy.grading.installed="$installed"
LABEL org.opencontainers.image.source="https://github.com/kspar/easy"
LABEL org.opencontainers.image.revision="${GITHUB_SHA:-unknown}"
EOF
    docker image rm "$staged" >/dev/null 2>&1 || true
    BUILT_DIGESTS="$BUILT_DIGESTS $digest"

    publish "$env" "$image" "$pinned"
}

# Push the immutable tag, a human-readable alias, and the environment's channel.
#
# The channel is what a host tracks, and it moves only here — after the build, the smoke test and
# every image this one is built on. "The channel moved" is therefore a claim that the image works,
# which is the whole reason the reconciler needs no access to CI or GitHub.
publish() {
    local env="$1" image="$2" pinned="$3"
    if [[ "$PUSH" != "true" ]]; then
        say "$env/$image: not publishing (PUSH=$PUSH)"
        return
    fi

    docker push -q "$pinned"

    local version
    version="$("$PINS" declared --env "$env" "$image" \
        | tr ' ' '\n' | grep -E "^$image==" | cut -d= -f3 || true)"
    if [[ -n "$version" ]]; then
        docker tag "$pinned" "$REGISTRY/$image:$version"
        docker push -q "$REGISTRY/$image:$version"
    fi

    docker tag "$pinned" "$REGISTRY/$image:$env"
    docker push -q "$REGISTRY/$image:$env"
    say "$env/$image: published, and the $env channel now points at i${pinned##*:i}"
}

main() {
    local order
    order="${IMAGES:-$("$PINS" order)}"

    for env in $ENVIRONMENTS; do
        # In dependency order, always: imgrec builds FROM pygrader, so both must be in the same
        # daemon under the bare name for that unqualified FROM to resolve.
        for image in $order; do
            build_one "$env" "$image"
        done
    done
    say "done: $ENVIRONMENTS / $order"
}

main "$@"
