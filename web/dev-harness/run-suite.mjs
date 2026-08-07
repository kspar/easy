/**
 * Runs the browser scripts that need no backend, owning the stub dev server's lifecycle so CI
 * (and anyone else) can invoke one command instead of juggling two terminals.
 *
 *   node dev-harness/run-suite.mjs               # everything, one at a time (what CI runs)
 *   node dev-harness/run-suite.mjs tsl           # only scripts whose filename matches "tsl"
 *   node dev-harness/run-suite.mjs tsl grade     # union of both filters
 *   node dev-harness/run-suite.mjs --jobs 4      # everything, four at a time
 *   node dev-harness/run-suite.mjs -j 4 library  # combined
 *
 * **Run a filtered subset while iterating.** The full suite takes minutes, and CI runs all of it
 * on every push anyway, so re-running everything locally after each edit buys very little and
 * costs real waiting. Run the script covering what you changed; let CI catch the rest.
 *
 * Exits non-zero if any script fails.
 *
 * With `--jobs 1` (the default) output streams live, which is what you want when a single script
 * is failing. Above that, each script's output is buffered and printed as one block when it
 * finishes — the scripts are independent (own browser, own screenshot prefix, read-only dev
 * server), so the only obstacle to running them together is machine load.
 *
 * **Keep `--jobs` at 2.** Measured on an M-series laptop: at 2 the suite is roughly 3x faster and
 * every script still passes; at 4 seven scripts failed and every per-script time roughly doubled,
 * because each script drives its own Chromium at deviceScaleFactor 2. Those failures are pure
 * contention and look exactly like real ones, so if anything fails under `--jobs > 1`, re-run it
 * on its own before believing it. CI stays sequential for that reason.
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
  'library-exercise-tsl-static.mjs',
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

function parseArgs(argv) {
  const filters = []
  let jobs = 1
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--jobs' || a === '-j') jobs = Number(argv[++i])
    else if (a.startsWith('--jobs=')) jobs = Number(a.slice('--jobs='.length))
    else filters.push(a.toLowerCase())
  }
  if (!Number.isInteger(jobs) || jobs < 1) throw new Error(`--jobs must be a positive integer`)
  return { filters, jobs }
}

const { filters, jobs } = parseArgs(process.argv.slice(2))

const selected = filters.length
  ? SCRIPTS.filter((s) => filters.some((f) => s.toLowerCase().includes(f)))
  : SCRIPTS

if (!selected.length) {
  console.error(`No script matches ${filters.join(', ')}. Available:\n  ${SCRIPTS.join('\n  ')}`)
  process.exit(1)
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

/** Resolves to `{ name, code, ms }`; `stream: false` buffers output and returns it instead. */
function runScript(name, stream) {
  return new Promise((resolve) => {
    const started = Date.now()
    const child = spawn(process.execPath, [join(HERE, 'scripts', name)], {
      cwd: HERE,
      stdio: stream ? 'inherit' : ['ignore', 'pipe', 'pipe'],
      env: { ...process.env, HARNESS_URL: BASE_URL },
    })
    const chunks = []
    child.stdout?.on('data', (d) => chunks.push(d.toString()))
    child.stderr?.on('data', (d) => chunks.push(d.toString()))
    child.on('exit', (code) =>
      resolve({ name, code: code ?? 1, ms: Date.now() - started, output: chunks.join('') }),
    )
  })
}

/** Runs `names` with at most `jobs` in flight, preserving completion reporting order per script. */
async function runAll(names, concurrency) {
  const results = []
  let next = 0
  const worker = async () => {
    for (;;) {
      const i = next++
      if (i >= names.length) return
      const name = names[i]
      if (concurrency === 1) log(`running ${name}`)
      const r = await runScript(name, concurrency === 1)
      if (concurrency > 1) {
        log(`${name} (${(r.ms / 1000).toFixed(1)}s)`)
        process.stdout.write(r.output)
      }
      results.push(r)
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, names.length) }, worker))
  return results
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
  if (filters.length) log(`filtered to ${selected.length}/${SCRIPTS.length}: ${selected.join(', ')}`)
  if (jobs > 2) {
    log(
      `\x1b[33mwarning:\x1b[0m --jobs ${jobs} oversubscribes the machine; failures above 2 are ` +
        `usually contention, not bugs. Re-run anything that fails on its own.`,
    )
  }

  const started = Date.now()
  const results = await runAll(selected, jobs)
  const wall = (Date.now() - started) / 1000

  // Slowest first: the point of printing these is to know what to skip next time.
  const slow = [...results].sort((a, b) => b.ms - a.ms)
  log(`timings: ${slow.map((r) => `${r.name} ${(r.ms / 1000).toFixed(1)}s`).join(', ')}`)

  const failed = results.filter((r) => r.code !== 0).map((r) => r.name)
  if (failed.length) {
    log(`\x1b[31mFAILED:\x1b[0m ${failed.join(', ')}`)
    exitCode = 1
  } else {
    log(`\x1b[32mall ${selected.length} scripts passed\x1b[0m in ${wall.toFixed(1)}s`)
  }
} catch (e) {
  console.error(e)
  exitCode = 1
} finally {
  server.kill('SIGTERM')
}

process.exit(exitCode)
