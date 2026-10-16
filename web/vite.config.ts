import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { decodeJwt } from './vite-jwt-proxy.ts'

/**
 * Version stamping (EZ-1709).
 *
 * The version comes from the repo-root `VERSION` file, which core and aae read too — one number for
 * the product rather than three that can disagree. `web/package.json` deliberately keeps its
 * meaningless `0.0.0`: it is not published to npm, and a second place to bump at release time is a
 * second place to forget.
 *
 * The commit is what tells you which *build* you are looking at, since several builds share a
 * version. `GITHUB_SHA` is the authority in CI; locally it asks git, and where neither exists it
 * says "unknown" rather than failing a build over a diagnostic string.
 */
function readVersion(): string {
  try {
    return readFileSync(new URL('../VERSION', import.meta.url), 'utf8').trim() || 'unknown'
  } catch {
    return 'unknown'
  }
}

function readCommit(): string {
  const fromCi = process.env.GITHUB_SHA
  if (fromCi) return fromCi.slice(0, 7)
  try {
    return execFileSync('git', ['rev-parse', '--short=7', 'HEAD'], { encoding: 'utf8' }).trim()
  } catch {
    return 'unknown'
  }
}

/**
 * The build stamp, read once so that the constants compiled into the bundle and the manifest
 * written beside it can never disagree — which is the whole basis of the update check in
 * `src/api/webVersion.ts`: it compares one against the other.
 */
const BUILD = {
  version: readVersion(),
  commit: readCommit(),
  builtAt: new Date().toISOString(),
}

/**
 * Writes `version.json` into the dist (EZ-1752).
 *
 * A running tab keeps the bundle it loaded until someone reloads it, so it needs a way to ask what
 * is deployed *now*. Core cannot answer that — web is deployed as static files and often changes
 * when core does not — so the dist describes itself, and the check is one static fetch with no
 * backend involved.
 *
 * Emitted by the build rather than written by the deploy script: an artifact that carries its own
 * identity works the same on every environment and needs nothing added to any deploy. It is the
 * opposite decision to `config.json`, which is deliberately *removed* from the artifact because it
 * differs per environment (EZ-1726). This file is the same everywhere the artifact goes, which is
 * exactly what makes it a build stamp.
 *
 * Build only: under `vite dev` there is no build to identify, and no file — the request falls
 * through to the SPA's index.html and the client treats the HTML it gets back as "nothing to say".
 */
function versionManifest(): Plugin {
  return {
    name: 'easy-version-manifest',
    apply: 'build',
    generateBundle() {
      this.emitFile({
        type: 'asset',
        fileName: 'version.json',
        source: JSON.stringify(BUILD, null, 2) + '\n',
      })
    },
  }
}

/**
 * Dev-only role override, read from the gitignored `web/.env.local`.
 *
 * Local dev signs into the *production* IdP (`public/config.json`), so the role that arrives in the
 * token is whatever that account really has. Core is running with `auth-enabled: false` and already
 * believes whatever the proxy tells it, so overriding the role header here grants nothing that
 * sending the header by hand would not (`doc/core/api-testing.md`).
 *
 * It changes what *core* sees, and only that. The SPA takes its own roles from the token
 * (`AuthContext`), so a developer who is a plain student at the IdP still sees the student UI and is
 * still turned away from teacher-only pages; what the override buys is API calls through the proxy
 * made with the given role. To use the teacher pages without a teacher account, run against the
 * Keycloak stub instead (`docker-compose.override.yml.sample`), whose role the SPA does read.
 *
 * Identity is deliberately left alone: you stay yourself, with different rights. Swapping the
 * username too would silently attribute your clicks to somebody else's account.
 *
 * Put `EASY_DEV_ROLE=teacher,admin` in `web/.env.local` to use it. Absent, this is inert, and it
 * cannot reach a deployed environment at all: a production build has no dev server and no proxy.
 */
function devLocalSetting(key: string): string | null {
  try {
    const raw = readFileSync(new URL('./.env.local', import.meta.url), 'utf8')
    // Parsed rather than matched with a regex. The regex version was built in a template literal,
    // where `\s` is not an escape sequence and collapses to a literal 's' — so every whitespace
    // class silently became "zero or more letter s", and an indented line, or one with spaces round
    // the `=`, failed to match with no diagnostic anywhere. Splitting on the first `=` has no
    // escaping to get wrong, skips comments, and `trim()` handles both indentation and a CRLF `\r`.
    for (const line of raw.split('\n')) {
      const trimmed = line.trim()
      if (!trimmed || trimmed.startsWith('#')) continue
      const separator = trimmed.indexOf('=')
      // `export KEY=value` is an ordinary .env spelling, and Vite's own dotenv reads it from this
      // same file; compared whole, the key was 'export KEY' and the line was silently skipped.
      const name = trimmed.slice(0, separator).trim().replace(/^export\s+/, '')
      if (separator < 0 || name !== key) continue
      // A trailing `# comment` and matched surrounding quotes are both ordinary .env spellings, and
      // either sent verbatim becomes a header value core answers with a 401 about an unmappable
      // role, with nothing pointing back here. Quotes come off first: inside them a `#` is data,
      // not the start of a comment, so stripping comments first would truncate `KEY="a#b"` to `"a`.
      // The closing quote is looked for rather than required at the end, so `KEY="a" # note` keeps
      // `a` — anchoring it to the end sent `"a"` with its quotes whenever a comment followed.
      const rest = trimmed.slice(separator + 1).trim()
      const quote = rest[0]
      const close = quote === '"' || quote === "'" ? rest.indexOf(quote, 1) : -1
      return (close > 0 ? rest.slice(1, close) : rest.split('#')[0].trim()) || null
    }
    return null
  } catch {
    return null
  }
}

const devRoleOverride = () => devLocalSetting('EASY_DEV_ROLE')

/**
 * Identity to fall back on when the bearer token carries none.
 *
 * Only reachable with the Keycloak stub (`vite.stub.config.ts`), whose token is the fixed string
 * `stub-token` — not a JWT, so `decodeJwt` returns `{}` and every `oidc_claim_*` header would go out
 * empty. Core refuses an incomplete set with a 401 by design, so without this the stub gives a
 * signed-in UI whose every request fails. With it, `npx vite --config vite.stub.config.ts` is a
 * complete local stack that never touches an IdP.
 *
 * `EASY_DEV_AS=username,email` in `web/.env.local`. A real token is never overridden.
 *
 * A role comes with it. The stub's token carries no `easy_role` either, so supplying only the
 * identity sent an empty role header - and core refuses a present-but-incomplete claim set with a
 * 401, which is the signed-in-UI-whose-requests-all-fail state this exists to prevent.
 * `EASY_DEV_ROLE` still wins when set; this is only the default for the stub path.
 */
function devIdentityFallback(): { username: string; email: string; role: string } | null {
  const raw = devLocalSetting('EASY_DEV_AS')
  const [username, email] = raw?.split(',').map((s) => s.trim()) ?? []
  if (!username || !email) return null
  return { username, email, role: devRoleOverride() ?? 'teacher,admin' }
}

export default defineConfig({
  define: {
    __APP_VERSION__: JSON.stringify(BUILD.version),
    __APP_COMMIT__: JSON.stringify(BUILD.commit),
    __APP_BUILT_AT__: JSON.stringify(BUILD.builtAt),
  },
  plugins: [react(), versionManifest()],
  /**
   * The CodeMirror language modes, pre-bundled rather than discovered.
   *
   * `languageFromFilename` imports these at runtime, one per file extension, so in dev Vite meets
   * them for the first time when a `.py` file is opened — and answers that first request with a
   * 504 "Outdated Optimize Dep" while it re-optimizes. A browser reloads and moves on; the code
   * does not, because the import failing leaves the editor never created. So the symptom was a
   * teacher's testing tab with no editor in it, and it took a browser test opening a `.py` file to
   * see it at all: every other editor in the app happens to open on `evaluate.sh` or `tsl.json`.
   *
   * Listing them here means the server pre-bundles all of them at startup, so no request ever
   * races an optimizer pass. Keep in step with `languageFromFilename` — a mode missing from this
   * list still works in a browser and still fails a test.
   */
  optimizeDeps: {
    include: [
      '@codemirror/lang-python',
      '@codemirror/lang-javascript',
      '@codemirror/lang-java',
      '@codemirror/lang-cpp',
      '@codemirror/lang-html',
      '@codemirror/lang-css',
      '@codemirror/lang-sql',
      // Same reasoning, same symptom (EZ-1732). `renderMath.ts` imports KaTeX only once a formula
      // is actually on the page — it is ~280KB plus a megabyte of fonts, and most pages have no
      // maths — so in dev the server meets it mid-session and answers the first request with a 504.
      // The import rejects, the catch swallows it, and the formula silently stays as `$x^2$`.
      'katex',
      // And again for the highlighter, which `highlightCode.ts` imports only once a language-tagged
      // block is on the page. The core and each language are separate entry points, so every one of
      // them has to be listed: an unlisted mode is a 504 on first use, a swallowed rejection, and
      // code that renders unhighlighted — which looks exactly like highlighting not being wired up.
      'highlight.js/lib/core',
      'highlight.js/lib/languages/python',
      'highlight.js/lib/languages/sql',
      'highlight.js/lib/languages/bash',
      'highlight.js/lib/languages/xml',
    ],
  },
  server: {
    proxy: {
      '/v2': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        configure: (proxy) => {
          // Translate the SPA's bearer token into the oidc_claim_* headers that a core
          // running with easy.core.auth-enabled=false expects, so browser-based local dev
          // needs no IdP at all. Deployed environments no longer work this way: core
          // verifies the JWT itself (see core/conf/security/EasyUserJwtConverter.kt), and
          // Apache in front of it is a plain reverse proxy that passes Authorization
          // through untouched. Point a local core at an IdP with auth-enabled=true and
          // this translation becomes unnecessary.
          proxy.on('proxyReq', (proxyReq, req) => {
            // Remove Origin header so backend CORS filter doesn't reject
            // requests from the Vite dev server (localhost:5173)
            proxyReq.removeHeader('origin')

            const authHeader = req.headers['authorization']
            if (!authHeader?.startsWith('Bearer ')) return

            const token = authHeader.slice(7)
            try {
              const claims = decodeJwt(token)
              const fallback = claims.preferred_username ? null : devIdentityFallback()
              proxyReq.setHeader('oidc_claim_preferred_username', fallback?.username ?? claims.preferred_username ?? '')
              proxyReq.setHeader('oidc_claim_email', fallback?.email ?? claims.email ?? '')
              proxyReq.setHeader('oidc_claim_given_name', claims.given_name ?? '')
              proxyReq.setHeader('oidc_claim_family_name', claims.family_name ?? '')
              const easyRole = Array.isArray(claims.easy_role)
                ? claims.easy_role.join(',')
                : (claims.easy_role ?? '')
              // A local override wins over the token's own claim; see devRoleOverride above.
              proxyReq.setHeader('oidc_claim_easy_role', devRoleOverride() ?? fallback?.role ?? easyRole)
              proxyReq.setHeader('OIDC_access_token', token)
            } catch {
              // invalid token, let backend handle it
            }
          })
        },
      },
    },
  },
})
