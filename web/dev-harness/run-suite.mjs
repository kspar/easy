/**
 * Runs the browser scripts that need no backend, owning the stub dev server's lifecycle so CI
 * (and anyone else) can invoke one command instead of juggling two terminals.
 *
 *   node dev-harness/run-suite.mjs          # from web/, or `npm test` from dev-harness/
 *
 * Exits non-zero if any script fails. Scripts run in sequence rather than in parallel: they
 * share one dev server and one port, and interleaved output would be unreadable.
 */
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const WEB = join(HERE, '..')
const PORT = process.env.HARNESS_PORT ?? '5199'
const BASE_URL = `http://localhost:${PORT}`

/**
 * Scripts safe to run with no backend — every request is stubbed by Playwright.
 *
 * `library-exercise-tsl-live.mjs` is deliberately absent: it relays /tsl/compile to a real core
 * on :8080 with auth disabled and the tiivad container registered, which CI has no way to
 * provide. It stays a local verification tool.
 */
const SCRIPTS = [
  'course-exercises.mjs',
  'library-page.mjs',
  'runtime-config.mjs',
  'library-exercise-tsl.mjs',
  'library-exercise-tsl-contains.mjs',
  'library-exercise-ui.mjs',
  'library-exercise-markdown.mjs',
  'embed-exercise.mjs',
  'course-exercise-embed.mjs',
  'course-exercise-retry-autoassess.mjs',
  'similarity-page.mjs',
  'terms-redirect.mjs',
  'grade-table.mjs',
  'account-settings.mjs',
]

function log(msg) {
  console.log(`\x1b[36m[run-suite]\x1b[0m ${msg}`)
}

/** Start the stub dev server as a direct node process, so killing it actually kills vite. */
function startServer() {
  const child = spawn(
    process.execPath,
    [
      join(WEB, 'node_modules/vite/bin/vite.js'),
      '--config',
      'vite.stub.config.ts',
      '--port',
      PORT,
      '--strictPort',
    ],
    { cwd: WEB, stdio: ['ignore', 'pipe', 'pipe'] },
  )
  const output = []
  child.stdout.on('data', (d) => output.push(d.toString()))
  child.stderr.on('data', (d) => output.push(d.toString()))
  return { child, output }
}

async function waitForServer(timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    try {
      const resp = await fetch(BASE_URL)
      if (resp.ok) return true
    } catch {
      // not up yet
    }
    if (Date.now() >= deadline) return false
    await new Promise((r) => setTimeout(r, 250))
  }
}

function runScript(name) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [join(HERE, 'scripts', name)], {
      cwd: HERE,
      stdio: 'inherit',
      env: { ...process.env, HARNESS_URL: BASE_URL },
    })
    child.on('exit', (code) => resolve(code ?? 1))
  })
}

const { child: server, output } = startServer()
let exitCode = 0

try {
  log(`starting stub dev server on ${BASE_URL}`)
  if (!(await waitForServer())) {
    console.error(output.join(''))
    throw new Error(`dev server did not come up on ${BASE_URL}`)
  }
  log('dev server ready')

  const failed = []
  for (const name of SCRIPTS) {
    log(`running ${name}`)
    const code = await runScript(name)
    if (code !== 0) failed.push(name)
  }

  if (failed.length) {
    log(`\x1b[31mFAILED:\x1b[0m ${failed.join(', ')}`)
    exitCode = 1
  } else {
    log(`\x1b[32mall ${SCRIPTS.length} scripts passed\x1b[0m`)
  }
} catch (e) {
  console.error(e)
  exitCode = 1
} finally {
  server.kill('SIGTERM')
}

process.exit(exitCode)
