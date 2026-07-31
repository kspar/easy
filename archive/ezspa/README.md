# ezspa — ARCHIVED

**This is not part of the build.** It has no `build.gradle.kts` and is not included in
`settings.gradle`. Nothing compiles it, nothing depends on it, and it will never run again.
It is kept here as source, because it exists nowhere else.

## What it was

A single-page-application framework for Kotlin/JS, written from scratch for this project.
About 970 lines. It was what [`:wui`](../../doc/wui) — the Kotlin/JS web UI that served
Lahendus until 2026 — was built on, before the UI was rewritten in React under [`web/`](../../web).

It was split out of `:wui` into its own module in v2.7 (EZ-1314) and published to jcenter as
`rip.kspar:ezspa:0.4.0`. jcenter is gone, so this directory is the copy that's left.

## What's in it

| File | Role |
| --- | --- |
| `EzSpa.kt` | Entry point. `PageManager` holds the registered pages, matches the current path to exactly one of them, and drives destruct → clear → build on navigation. |
| `Page.kt` | The page abstraction: path matching, authorisation assertion, build/destruct lifecycle, page state. |
| `Component.kt` | The component tree — nested components, `createAndBuild`, `rebuild`, child lifecycle. |
| `CacheableComponent.kt` | Components that could serialise their state and render from cache on the next visit. |
| `Navigation.kt`, `navigation_interception.kt` | Anchor-link and browser history interception, so `<a href>` stayed a real link but didn't reload the page. |
| `dom_interaction.kt`, `dom_search.kt`, `props.kt` | Typed-ish DOM helpers over the raw Kotlin/JS browser API. |
| `IdGenerator.kt`, `async.kt`, `jshacks.kt`, `path_components.kt` | Supporting odds and ends. |

## Build config it used to have

For the record, since the file was removed:

```kotlin
plugins { kotlin("js") }
group = "rip.kspar"; version = "0.4.0"
dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-js:1.5.2") }
kotlin { js(IR) { browser {}; binaries.executable() } }
```

Restoring it would also mean re-adding `kotlin("js")` to the root `build.gradle.kts` and an
`include` in `settings.gradle` — both were removed when the module was archived.
