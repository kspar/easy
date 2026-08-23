import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { loadConfig, ConfigError } from './config.ts'
import { applyEnvironmentBadge } from './environment.ts'
import { installBreadcrumbs } from './features/bug-report/breadcrumbs.ts'

/**
 * Shown instead of the app when /config.json is missing or malformed. Deliberately plain DOM:
 * the whole point is that it works when the app has not loaded, so it must not depend on React,
 * the theme, or i18n.
 */
function renderConfigError(root: HTMLElement, message: string) {
  root.innerHTML = ''
  const box = document.createElement('div')
  box.style.cssText =
    'font:16px/1.5 system-ui,sans-serif;max-width:40rem;margin:4rem auto;padding:0 1.5rem'
  const heading = document.createElement('h1')
  heading.style.cssText = 'font-size:1.25rem;margin:0 0 .75rem'
  heading.textContent = 'Configuration error'
  const detail = document.createElement('p')
  detail.style.cssText = 'margin:0 0 .75rem'
  detail.textContent = message
  const hint = document.createElement('p')
  hint.style.cssText = 'margin:0;color:#666'
  hint.textContent =
    'This deployment is missing a valid config.json next to index.html. See web/README.md.'
  box.append(heading, detail, hint)
  root.append(box)
}

const root = document.getElementById('root')!

// First, before anything else can throw. Config loading and the app's own module evaluation are
// both inside the window this covers, and a failure in either is precisely the kind that leaves a
// blank page with nothing to report (EZ-1786).
installBreadcrumbs()

try {
  // Must complete before the app is imported: AuthContext.tsx builds its Keycloak instance at
  // module scope, so a static `import App from './App.tsx'` would evaluate it with an empty
  // config. Hence the dynamic import below rather than a top-level one.
  await loadConfig()
} catch (e) {
  const message =
    e instanceof ConfigError ? e.message : `Unexpected error loading config: ${String(e)}`
  console.error(message)
  renderConfigError(root, message)
  throw e
}

// Before the app renders, so a dev tab is marked as such from the first paint rather than
// after the first route sets its own title (EZ-1733). Does nothing on production.
applyEnvironmentBadge()

const { default: App } = await import('./App.tsx')

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
