/**
 * Unit tests: pure functions, no browser, no framework beyond this one.
 *
 *   npm run test:unit
 *   npx vitest --watch          # while iterating
 *   npx vitest --coverage       # report-only, no threshold
 *
 * Vitest resolves the TypeScript sources itself, which is the entire reason it replaced the
 * hand-rolled runner: that runner bundled every file with esbuild first, solely so Node 20 could
 * import a `.ts` file. The bundling step and the esbuild dependency are both gone.
 *
 * **What does not belong here: component tests.** No jsdom, on purpose. The bugs this app actually
 * ships need layout and the cascade — an unresolvable `sx` token producing no stroke,
 * `animation-fill-mode` outranking a transition, MUI only wiring InputLabel→Select when both carry
 * ids, a `<button>` nested inside another. jsdom catches none of those while duplicating the
 * browser suite at lower fidelity. Logic worth testing in isolation gets extracted into a plain
 * module and tested here; anything that needs a DOM goes in tests/browser/.
 */
import { defineConfig } from 'vitest/config'

export default defineConfig({
  // The build-time constants vite's `define` substitutes (see vite.config.ts). Without them,
  // importing any module that reads one — api/webVersion.ts, features/about/AboutPage.tsx — dies
  // with a ReferenceError at module scope, before a single assertion runs. Stub values rather than
  // real ones, because a unit test that depended on which commit it was built from would be a test
  // that fails on someone else's machine.
  define: {
    __APP_VERSION__: '"0.0-test"',
    __APP_COMMIT__: '"testing"',
    __APP_BUILT_AT__: '"2026-01-01T00:00:00.000Z"',
  },
  test: {
    include: ['tests/unit/**/*.test.mjs'],
    // Report-only. A global coverage threshold would be a trap: a large share of src/ is component
    // markup that this suite deliberately does not reach, so the number measures the ratio of
    // markup to logic rather than anything about risk.
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      reporter: ['text-summary', 'html'],
      reportsDirectory: 'coverage',
    },
  },
})
