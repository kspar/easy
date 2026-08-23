/**
 * Unit S1 — the theme as a system: what a token-level contrast fix would actually buy.
 *
 * X-004 and C5's sweep established *that* the palette fails AA in several places. A design finding
 * owes an alternative, and "darken the green" is not an alternative until someone has checked which
 * darker green clears 4.5:1 against white text and what it does to the other pairings. This is pure
 * arithmetic over the values in `theme.ts`, so it needs no browser — WCAG 2.x relative luminance and
 * contrast ratio, which is a formula, not an opinion.
 *
 *   node tests/audit/s1-token-contrast.mjs
 */
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { REPORTS } from './audit.mjs'

/** WCAG 2.x relative luminance. */
function luminance(hex) {
  const h = hex.replace('#', '')
  const full = h.length === 3 ? h.split('').map((c) => c + c).join('') : h
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(full.slice(i, i + 2), 16) / 255)
  const f = (c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)
}

function ratio(a, b) {
  const [la, lb] = [luminance(a), luminance(b)]
  const [hi, lo] = la > lb ? [la, lb] : [lb, la]
  return (hi + 0.05) / (lo + 0.05)
}

const r2 = (x) => Math.round(x * 100) / 100
/** AA: 4.5 for normal text, 3.0 for large (>=24px, or >=18.66px bold) and for UI component borders. */
const verdict = (x) => (x >= 4.5 ? 'AA' : x >= 3 ? 'AA-large-only' : 'fail')

// From web/src/theme/theme.ts at 1bfdaf9d.
const GREEN = {
  50: '#f0fdf4', 100: '#dcfce7', 200: '#bbf7d0', 300: '#86efac', 400: '#4ade80',
  500: '#22c55e', 600: '#16a34a', 700: '#15803d', 800: '#166534', 900: '#14532d',
}
const BG = { lightDefault: '#f5f5f5', lightPaper: '#ffffff', darkDefault: '#121212', darkPaper: '#1e1e1e' }
const TEXT = { lightPrimary: '#212121', lightSecondary: '#757575', darkPrimary: '#e0e0e0', darkSecondary: '#9e9e9e' }

const out = []
const add = (label, fg, bg) => out.push({ label, fg, bg, ratio: r2(ratio(fg, bg)), verdict: verdict(ratio(fg, bg)) })

console.log('=== 1. white text on the brand green, and on each darker step ===')
console.log('   (primary.contrastText is hardcoded #fff; 26 use sites)')
for (const step of [500, 600, 700, 800, 900]) {
  add(`#fff on GREEN[${step}]`, '#ffffff', GREEN[step])
}

console.log('\n=== 2. the brand green AS TEXT, on both light backgrounds ===')
add('GREEN[600] on light default', GREEN[600], BG.lightDefault)
add('GREEN[600] on light paper', GREEN[600], BG.lightPaper)
add('GREEN[700] on light default', GREEN[700], BG.lightDefault)
add('GREEN[700] on light paper', GREEN[700], BG.lightPaper)
add('GREEN[800] on light paper', GREEN[800], BG.lightPaper)

console.log('\n=== 3. text.secondary — 177 use sites, the most-used token in the app ===')
add('text.secondary light on default', TEXT.lightSecondary, BG.lightDefault)
add('text.secondary light on paper', TEXT.lightSecondary, BG.lightPaper)
add('text.secondary dark on default', TEXT.darkSecondary, BG.darkDefault)
add('text.secondary dark on paper', TEXT.darkSecondary, BG.darkPaper)
console.log('   candidate replacements for the light value:')
for (const c of ['#6b6b6b', '#666666', '#616161', '#5f5f5f', '#595959']) {
  add(`candidate ${c} on light default`, c, BG.lightDefault)
}

console.log('\n=== 4. text.primary, as a control — this one should pass comfortably ===')
add('text.primary light on default', TEXT.lightPrimary, BG.lightDefault)
add('text.primary dark on default', TEXT.darkPrimary, BG.darkDefault)

console.log('\n=== 5. the brand green in DARK mode, which no token switches ===')
add('GREEN[600] on dark default', GREEN[600], BG.darkDefault)
add('GREEN[600] on dark paper', GREEN[600], BG.darkPaper)
add('GREEN[500] on dark paper', GREEN[500], BG.darkPaper)
add('GREEN[400] on dark paper', GREEN[400], BG.darkPaper)
add('#fff on GREEN[600] (dark, unchanged)', '#ffffff', GREEN[600])

for (const o of out) {
  console.log(`  ${o.ratio.toFixed(2).padStart(5)}:1  ${o.verdict.padEnd(14)} ${o.label}  (${o.fg} on ${o.bg})`)
}

const reportPath = join(REPORTS, 's1-token-contrast.json')
writeFileSync(reportPath, JSON.stringify({ sha: process.env.AUDIT_SHA ?? 'unknown', pairs: out }, null, 2))
console.log(`\nreport written to ${reportPath}`)
