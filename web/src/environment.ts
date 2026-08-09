/**
 * Telling one environment from another at a glance (EZ-1733).
 *
 * With staging live, people keep two or three tabs open on the same application: same layout, same
 * green, same favicon, same "Lahendus" in the title bar, with only a hostname to tell them apart.
 * The expensive mistake — deleting a course or resolving a submission on production while
 * believing you are on staging — is made by *clicking the wrong tab*, and a tab shows nothing but
 * a favicon and a truncated title. Hence three signals, of which two live here:
 *
 * 1. a banner above the app bar (`components/EnvironmentBanner.tsx`), for when you are looking at
 *    the page rather than the tab;
 * 2. a **title prefix** — `[STAGING] Lahendus` — which also shows up in the window switcher and in
 *    browser history;
 * 3. a **tinted favicon**, which is the one that actually catches the wrong-tab case.
 *
 * All of it is driven by `environment` in config.json, so production — where the key is absent —
 * is undecorated and needs no configuration to stay that way.
 */
import config from './config.ts'

/**
 * The tab title, with the environment prefix when there is one — `[STAGING] My courses - Lahendus`.
 *
 * In front of the *whole* title, not in front of "Lahendus": a tab strip with several tabs open
 * truncates from the right, so anything but the leading position disappears exactly when it is
 * needed. Putting it before the page name loses one word of that name and keeps the marking.
 */
export function documentTitle(pageTitle?: string): string {
  const title = pageTitle ? `${pageTitle} - ${config.appName}` : config.appName
  return config.environment ? `[${config.environment.label}] ${title}` : title
}

/**
 * The Lahendus glyph, copied from `assets/logo.svg` rather than imported: that import resolves to
 * a URL, and this favicon is built as text so that any colour works without shipping an icon file
 * per environment. One built dist has to serve every environment (EZ-1726), so a "staging icon
 * set" in `public/` could only ever cover one predetermined colour.
 *
 * If the logo ever changes, this string is the second place to change.
 */
const LOGO_PATH =
  'M11 0l6 4.8V0zm6 4.8V7 4.8zM7 0v10.1h10V7L8.4 0zm6.9 13.9h10V24H14zM0 13.9h10.1V24H.1z'

/**
 * A filled badge with the glyph knocked out in white, rather than the usual glyph in another
 * colour. At 16 pixels in a tab strip a solid block of colour is what the eye catches, and it
 * cannot be confused with production's icon, which is a green shape on transparency.
 */
function faviconSvg(colour: string): string {
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">` +
    `<rect width="24" height="24" rx="5" fill="${colour}"/>` +
    `<g transform="translate(4.5 4.5) scale(0.625)"><path d="${LOGO_PATH}" fill="#fff"/></g>` +
    `</svg>`
  )
}

/**
 * Set the tab title and, on a non-production environment, swap the favicon for a tinted one.
 *
 * Called from `main.tsx` once config has loaded, before the app is imported — so the title is
 * right from the first paint rather than after the first route renders.
 *
 * The replacement is an SVG data URI, which every current browser accepts as a favicon. Somewhere
 * old enough to refuse it shows no icon at all on non-production rather than a wrong one, and the
 * title prefix and the banner still say where you are.
 */
export function applyEnvironmentBadge(): void {
  document.title = documentTitle()

  const env = config.environment
  if (!env) return

  // Every icon link in index.html, not just the 32x32 one: leaving any of them behind means the
  // browser is free to keep showing a green Lahendus icon on staging, which is the one outcome
  // this cannot have. `rel~="icon"` covers `icon` and `shortcut icon`; the other two are single
  // words that it deliberately does not match.
  document
    .querySelectorAll('link[rel~="icon"], link[rel="apple-touch-icon"], link[rel="mask-icon"]')
    .forEach((link) => link.remove())

  const link = document.createElement('link')
  link.rel = 'icon'
  link.type = 'image/svg+xml'
  link.href = `data:image/svg+xml,${encodeURIComponent(faviconSvg(env.colour))}`
  document.head.append(link)
}

/**
 * Black or white, whichever is readable on `hex`.
 *
 * The banner's colour comes from a config file, so the text colour cannot be a constant: someone
 * marking a third environment in pale yellow should get readable text, not white on white. Plain
 * relative luminance with the sRGB gamma step, which is what WCAG contrast is built on.
 */
export function contrastText(hex: string): string {
  const h = hex.replace('#', '')
  const full = h.length === 3 ? [...h].map((c) => c + c).join('') : h
  const channel = (i: number) => {
    const v = parseInt(full.slice(i * 2, i * 2 + 2), 16) / 255
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4
  }
  const luminance = 0.2126 * channel(0) + 0.7152 * channel(1) + 0.0722 * channel(2)
  return luminance > 0.45 ? 'rgba(0, 0, 0, 0.87)' : '#fff'
}
