/**
 * Reload once when the app's own assets are gone (EZ-1908).
 *
 * A browser discards the page behind an idle tab to reclaim memory, and re-navigates to the same
 * URL when the person comes back to it. That navigation may reuse the stored document instead of
 * re-fetching it, so a tab left open across a deploy can boot an `index.html` whose hashed entry
 * chunk no longer exists. `location /assets/` answers `=404`, no script runs, and the result is a
 * blank page with nothing on it to explain itself — including the update banner from EZ-1752,
 * whose code is in the chunk that never loaded.
 *
 * `Cache-Control: no-store` on the document (roles/nginx) is the other half of this and should
 * make the case above stop happening on our own hosts. This file is the half that does not depend
 * on which browser, which proxy, or which server is in front of the dist.
 *
 * ### Why it is a separate file, and plain
 *
 * The thing that fails is the module script, so the handler has to be registered *before* it and
 * survive its failure. Inline is not available — the site's policy is `script-src 'self'` — so it
 * is a real request, which is also why it is small, dependency-free and written to run before any
 * transpilation exists.
 *
 * ### What it deliberately does not do
 *
 * Reload a tab that has already rendered. EZ-1752 settled that this app never reloads itself out
 * from under anyone, because a student's half-written solution lives in the editor until they
 * submit it, and that still holds. A blank page has nothing to lose, so it is reloaded; a live tab
 * that meets a missing chunk later — a CodeMirror mode, KaTeX, highlight.js — raises
 * `easy:asset-missing` instead, which `UpdateAvailableBanner` turns into a reload someone can
 * choose. The real fix for that case is keeping the previous release's assets reachable after a
 * deploy; until then, saying so beats failing silently.
 */
;(function () {
  'use strict'

  /**
   * That an automatic reload has already been spent on this tab.
   *
   * Per tab and not per browser, because two tabs restored from the same stale document each
   * deserve their own attempt. Cleared by `booted()` below, so the marker means exactly one thing:
   * we reloaded, and the app still has not started.
   */
  var MARKER = 'easyAssetReloadAt'

  /** Within one page load, so a page whose script and stylesheet both 404 reloads once. */
  var reloading = false

  /**
   * Both storage calls fail closed — no memory means no way to bound a reload loop, and a tab that
   * reloads forever is a worse failure than the blank page this is here to fix. Storage can be
   * genuinely unavailable: Safari private browsing and an iframe with third-party cookies blocked
   * both throw on access rather than returning null.
   */
  function alreadyReloaded() {
    try {
      return sessionStorage.getItem(MARKER) !== null
    } catch (e) {
      return true
    }
  }

  function rememberReload() {
    try {
      sessionStorage.setItem(MARKER, String(Date.now()))
      return true
    } catch (e) {
      return false
    }
  }

  /**
   * Is this URL one of ours?
   *
   * Both halves matter. `index.html` links a Google Fonts stylesheet, so an ad blocker or a
   * captive portal failing that must not reload a working page — hence the origin check. And
   * everything the build emits lives under `assets/`, so anything else on this origin that fails
   * to load is not a stale-bundle symptom. `indexOf` rather than a prefix match because the dist
   * can be served from a sub-path.
   */
  function isOwnAsset(url) {
    if (!url) return false
    try {
      var parsed = new URL(url, document.baseURI)
      return parsed.origin === window.location.origin && parsed.pathname.indexOf('/assets/') !== -1
    } catch (e) {
      return false
    }
  }

  function failedAssetUrl(target) {
    if (!target || !target.tagName) return null
    var tag = target.tagName
    // SCRIPT covers the entry module; LINK covers the stylesheet and the modulepreloads beside it.
    if (tag !== 'SCRIPT' && tag !== 'LINK') return null
    return target.src || target.href || null
  }

  /**
   * Tell the running app that one of its own files is gone (EZ-1908).
   *
   * This is the only reliable moment anyone learns it. The dynamic imports that fail this way —
   * a CodeMirror mode, KaTeX, the highlighter — are all loaded behind a `catch` that turns the
   * failure into "no syntax colouring" or "the formula stayed as `$x^2$`", which looks like a
   * feature that was never wired up rather than a tab running a bundle that no longer exists.
   * `UpdateAvailableBanner` listens and says so, instead of leaving the poll to notice minutes
   * later or never.
   */
  function announce(what) {
    try {
      window.dispatchEvent(new CustomEvent('easy:asset-missing', { detail: { url: what } }))
    } catch (e) {
      // A notification is not worth an exception on top of the failure that caused it.
    }
  }

  function reloadOnce(what) {
    if (reloading) return

    // The app is up, so something is on screen and possibly typed into. Say so and stop — but tell
    // the app first, because this is the one moment it can be certain its bundle is stale.
    if (window.__easyAppBooted) {
      console.warn('An app asset failed to load, but the app is running — not reloading: ' + what)
      announce(what)
      return
    }

    // A reload while offline replaces the blank page with the browser's network error page, which
    // is not an improvement and loses the URL from view.
    if (navigator.onLine === false) return

    if (alreadyReloaded()) {
      console.error('An app asset is still missing after a reload: ' + what)
      return
    }
    if (!rememberReload()) return

    reloading = true
    console.warn('An app asset is missing, which usually means a deploy. Reloading: ' + what)
    window.location.reload()
  }

  // Capture phase: a failed subresource fires `error` on its own element and does not bubble, so a
  // listener on window only sees it on the way down. Plain runtime errors arrive here too, with
  // window as the target, and `failedAssetUrl` filters them out.
  window.addEventListener(
    'error',
    function (event) {
      var url = failedAssetUrl(event.target)
      if (isOwnAsset(url)) reloadOnce(url)
    },
    true,
  )

  // Vite's preload helper raises this when a dynamic import cannot be fetched, which is how the
  // entry module's own `import('./App.tsx')` fails. The URL is on the event's payload where there
  // is one; the message is a diagnostic, not a decision.
  window.addEventListener('vite:preloadError', function (event) {
    var payload = event && event.payload
    reloadOnce((payload && payload.message) || 'a dynamic import')
  })

  /**
   * Called by `main.tsx` once React has rendered. From here on a missing asset is somebody's live
   * session rather than a failed boot, and the spent-reload marker is cleared so that the *next*
   * stale document — days later, after another deploy — gets its own attempt.
   */
  window.__easyBootGuard = {
    booted: function () {
      window.__easyAppBooted = true
      try {
        sessionStorage.removeItem(MARKER)
      } catch (e) {
        // Nothing to clear if storage is unavailable, and the guard already fails closed.
      }
    },
  }
})()
