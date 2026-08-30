/**
 * Verification driver for EZ-1798 (one green): the palette decision's AA claims, recomputed from
 * the values `theme.ts` actually carries — read from the file, so this cannot pass against stale
 * constants the way a hardcoded table would.
 *
 *   node tests/audit/ez1798-one-green-verify.mjs
 */
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { execFileSync } from 'node:child_process'

const HERE = dirname(fileURLToPath(import.meta.url))
const SRC = join(HERE, '../../src')
const theme = readFileSync(join(HERE, '../../src/theme/theme.ts'), 'utf8')
const indexHtml = readFileSync(join(HERE, '../../index.html'), 'utf8')
const manifest = readFileSync(join(HERE, '../../public/site.webmanifest'), 'utf8')

// The ramp as theme.ts actually declares it — parsed, not copied, so a retuned or typoed ramp
// fails here instead of being silently re-blessed by stale constants.
const ramp = {}
for (const m of theme.matchAll(/(\d+):\s*'(#[0-9a-f]{6})'/gi)) ramp[m[1]] = m[2].toLowerCase()

let failures = 0
const check = (ok, label) => {
  console.log(`  ${ok ? '✓' : '✗ FAIL'} ${label}`)
  if (!ok) failures++
}

// --- WCAG 2.x arithmetic --------------------------------------------------------------------------
const srgb = (hex) => {
  const n = hex.replace('#', '')
  return [0, 2, 4].map((i) => {
    const c = parseInt(n.slice(i, i + 2), 16) / 255
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  })
}
const lum = (hex) => {
  const [r, g, b] = srgb(hex)
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}
const ratio = (a, b) => {
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}
const aa = (fg, bg, label) => {
  const r = ratio(fg, bg)
  check(r >= 4.5, `${label}: ${r.toFixed(2)}:1 (AA needs 4.50)`)
}

// --- the theme carries the decided values ---------------------------------------------------------
check(ramp['700'] === '#15803d' && ramp['500'] === '#22c55e', 'the ramp itself is the decided one')
check(/main:\s*GREEN\[700\]/.test(theme), 'primary.main is GREEN[700]')
check(/secondary:\s*\{\s*main:/.test(theme) === false, 'the dead secondary triplet is gone')
check(!/light:\s*GREEN\[50\]|#fff8e1|#ffebee|#e3f2fd/.test(theme), 'the four dead *.light tints are gone')
check(!/Array\(16\)\.fill/.test(theme), 'the bespoke shadow scale is gone')
check(theme.includes("secondary: '#6b6b6b'"), 'light text.secondary is #6b6b6b')
check(!/rgba\(76,\s*175,\s*80/.test(theme), 'the Material-green selected states are gone')

// The shade rule is applied in the theme, not left as prose: everything rendering primary as
// small text on dark steps up the ramp — one named constant, four consumers.
check(/const PRIMARY_ON_DARK = GREEN\[500\]/.test(theme), 'the shade rule has its one named value')
const muiTabBlock = theme.slice(theme.indexOf('MuiTab:'), theme.indexOf('MuiTabs:'))
check(/Mui-selected.*PRIMARY_ON_DARK/s.test(muiTabBlock), 'selected tabs use the shade rule on dark')
check(/textPrimary: \{ color: PRIMARY_ON_DARK \}/.test(theme), 'text buttons use the shade rule on dark')
check(/outlinedPrimary: \{ color: PRIMARY_ON_DARK \}/.test(theme), 'outlined buttons use the shade rule on dark')
check(/MuiChip-outlined.*PRIMARY_ON_DARK/s.test(theme), 'outlined primary chips use the shade rule on dark')
check(
  /props: \{ color: 'primary' \}, style: \{ color: PRIMARY_ON_DARK \}/.test(theme),
  'links use the shade rule on dark, scoped to color=primary',
)

// The brand colour is present where it must be, not merely the old one absent.
check(indexHtml.includes('#15803d') && manifest.includes('#15803d'), 'index.html and the manifest carry the brand green')

// A repo-wide stray-green sweep: the retired families anywhere under src/ fail the run. This is
// the check that caught AutogradeAnimation and LandingPage still off-family.
let stray = ''
try {
  stray = execFileSync('grep', [
    '-rn', '-E', "#43a047|#4caf50|#81c784|76,\\s*175,\\s*80|from '@mui/material/colors'|#16a34a|#2d6a11",
    SRC, '--include=*.tsx', '--include=*.ts',
  ]).toString()
} catch {
  // grep exits 1 on no matches — the pass case.
}
// GREEN[600] #16a34a is allowed exactly once: the ramp declaration in theme.ts.
const strayLines = stray.split('\n').filter((l) => l.trim() && !/theme\.ts:\s*\d*.*600: '#16a34a'/.test(l) && !l.includes("600: '#16a34a'"))
check(strayLines.length === 0, `no retired green family anywhere under src/ (${strayLines.length} hits)${strayLines.length ? '\n    ' + strayLines.slice(0, 5).join('\n    ') : ''}`)

// --- the AA claims, on the values the theme declares ----------------------------------------------
const G700 = ramp['700']
const G500 = ramp['500']
aa('#ffffff', G700, 'white text on the primary button (both modes)')
aa(G700, '#f5f5f5', 'green text on the light page background')
aa(G700, '#ffffff', 'green text on light paper')
aa('#6b6b6b', '#f5f5f5', 'secondary text on the light page background (X-013)')
aa('#6b6b6b', '#ffffff', 'secondary text on light paper')
aa(G500, '#121212', 'primary.light as green text on the dark background (the shade rule)')
aa(G500, '#1e1e1e', 'primary.light as green text on dark paper')

console.log(failures === 0 ? '\nEZ-1798 verification: all checks passed' : `\nEZ-1798 verification: ${failures} FAILURES`)
process.exit(failures === 0 ? 0 : 1)
