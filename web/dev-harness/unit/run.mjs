/**
 * Runs the unit tests in this directory.
 *
 *   npm run test:unit          # from web/
 *
 * They import the TypeScript sources directly, which Node can only do from 22.6 onwards — and CI
 * runs the same Node 20 the rest of the build uses. So each file is bundled with esbuild first,
 * which resolves the `.ts` import and strips the types, and the result is run on whatever Node is
 * present. esbuild is already in the tree as vite's own bundler; it is a declared devDependency
 * here rather than a borrowed transitive one.
 *
 * Each test runs in its own process, so one failing suite cannot take the others' output with it
 * and an exit code means what it says. Adding a file to this directory is enough to run it.
 */
import { build } from 'esbuild'
import { spawn } from 'node:child_process'
import { mkdtemp, readdir, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const SELF = 'run.mjs'

const files = (await readdir(HERE))
  .filter((f) => f.endsWith('.mjs') && f !== SELF && !f.startsWith('.'))
  .sort()

if (files.length === 0) {
  console.error('No unit test files found in', HERE)
  process.exit(1)
}

const outDir = await mkdtemp(join(tmpdir(), 'easy-unit-'))

function run(file) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [file], { stdio: 'inherit' })
    child.on('exit', (code) => resolve(code ?? 1))
  })
}

let failed = 0
try {
  for (const file of files) {
    const out = join(outDir, file)
    await build({
      entryPoints: [join(HERE, file)],
      outfile: out,
      bundle: true,
      platform: 'node',
      format: 'esm',
      // The build-time constants vite's `define` substitutes (see vite.config.ts). esbuild is
      // standing in for vite here, so it has to stand in for this too: without them, importing any
      // module that reads one — `api/webVersion.ts`, `features/about/AboutPage.tsx` — dies with a
      // ReferenceError at module scope, before a single assertion runs. Stub values rather than
      // real ones, because a unit test that depended on which commit it was built from would be a
      // test that fails on someone else's machine.
      define: {
        __APP_VERSION__: '"0.0-test"',
        __APP_COMMIT__: '"testing"',
        __APP_BUILT_AT__: '"2026-01-01T00:00:00.000Z"',
      },
      // Keeps stack traces pointing at the real source rather than the bundle.
      sourcemap: 'inline',
      logLevel: 'error',
    })
    console.log(`\n\x1b[36m[unit]\x1b[0m ${file}`)
    const code = await run(out)
    if (code !== 0) failed++
  }
} finally {
  await rm(outDir, { recursive: true, force: true })
}

console.log(
  failed === 0
    ? `\n\x1b[32m[unit]\x1b[0m all ${files.length} files passed`
    : `\n\x1b[31m[unit]\x1b[0m ${failed} of ${files.length} files failed`,
)
process.exit(failed === 0 ? 0 : 1)
