/**
 * Checks the fixtures our browser tests answer with against the shapes core actually returns.
 *
 * ### The gap this closes
 *
 * Every browser script stubs the backend from objects we wrote by hand, and until now nothing
 * checked those objects still resemble the real thing. The failure mode is **a green suite and a
 * broken app**, and it has happened: a fixture kept `anonymous_autoassess_template: null` after the
 * column became non-nullable, and neither side noticed, because both sides were ours.
 *
 * `doc/core/api-shapes.json` is generated from core's Kotlin by reflection (see
 * `core/src/test/kotlin/core/contract/GenerateApiShapes.kt`) and committed. This reads it.
 *
 * ### How it retrofits every script without editing any of them
 *
 * Endpoint identity is **derived from the request URL**, not declared by the script. So `fakeApi`
 * can look up the shape for whatever the app just asked for, and all 28 existing scripts are
 * covered the moment this is wired in. A script that had to name its endpoints would have been 28
 * edits and a permanent tax on writing the 29th.
 *
 * ### The severity ladder, and the mistake in the first version of it
 *
 * The obvious ladder makes a *missing* non-nullable field a failure — core always sends it, so a
 * fixture without it describes a response core cannot produce. Running that against the real suite
 * produced 19 failures in one script, and **every one of them was correct behaviour**: a script
 * stubs the fields the page reads and leaves out the rest, which is not drift, it is how you write
 * a readable fixture. Shipping it would have meant either 28 broken scripts or a rule everyone
 * learns to silence.
 *
 * So the line is drawn at **values that are actually wrong**, not fixtures that are merely partial:
 *
 * | finding | severity | why |
 * | --- | --- | --- |
 * | `null` where core says non-nullable | **fail** | the documented bug, exactly |
 * | wrong kind (`"5"` where a number is sent) | **fail** | the app will do string maths on it |
 * | enum value core cannot produce | **fail** | a branch that can never be taken in production |
 * | key absent in a **response** | warn | partial stubs are normal and good |
 * | key absent in a **request** | ignored | a PATCH omitting a field *is* the protocol |
 * | key no DTO declares | warn | often drift, sometimes deliberate over-stubbing |
 * | no shape matched the URL | ignored | not every request is a v2 endpoint |
 *
 * Warnings are **ratcheted** per script against `contract-baseline.json`: the count may fall, and
 * exceeding it fails. Existing noise gets paid down rather than muted, and new noise cannot be
 * added quietly. An unknown key is the interesting half of that list — a fixture naming a field core
 * does not send usually means the field was renamed and the app is reading something that will be
 * `undefined` in production.
 */
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const SHAPES_PATH = join(HERE, '../../doc/core/api-shapes.json')

let shapes = null

export function loadShapes() {
  if (shapes) return shapes
  shapes = JSON.parse(readFileSync(SHAPES_PATH, 'utf8'))
  return shapes
}

/**
 * The endpoint whose path template matches `pathname`, or null.
 *
 * Templates hold `{name}` placeholders; a placeholder matches exactly one segment. Where several
 * match, the one with the most literal segments wins — `/v2/exercises/{id}/anonymous/details` must
 * beat `/v2/exercises/{id}` rather than losing to whichever was iterated first. That precedence is
 * the same trap the harness doc records for stub ordering, and it bites here for the same reason.
 */
export function matchEndpoint(method, pathname) {
  const all = loadShapes().endpoints
  let best = null
  let bestLiterals = -1

  for (const key of Object.keys(all)) {
    const [keyMethod, template] = key.split(' ')
    if (keyMethod !== method && keyMethod !== 'ANY') continue

    const t = template.split('/')
    const p = pathname.split('/')
    if (t.length !== p.length) continue

    let literals = 0
    let ok = true
    for (let i = 0; i < t.length; i++) {
      if (t[i].startsWith('{')) continue
      if (t[i] !== p[i]) { ok = false; break }
      literals++
    }
    if (ok && literals > bestLiterals) {
      best = { key, ...all[key] }
      bestLiterals = literals
    }
  }
  return best
}

const KIND_CHECK = {
  string: (v) => typeof v === 'string',
  number: (v) => typeof v === 'number',
  boolean: (v) => typeof v === 'boolean',
  // Serialised as ISO strings by DateTimeSerializer.
  datetime: (v) => typeof v === 'string',
  enum: (v) => typeof v === 'string',
  array: (v) => Array.isArray(v),
  object: (v) => typeof v === 'object' && v !== null && !Array.isArray(v),
  // Byte arrays, maps, anything the generator could not see into. Nothing to assert.
  opaque: () => true,
}

/**
 * Validate `value` against the named type, returning `{severity, message}` issues.
 *
 * Depth-limited rather than unbounded: a self-referencing DTO (a dir with a parent that is a dir)
 * would otherwise recurse forever on a fixture that happens to nest deeply.
 */
export function validate(value, typeName, where, depth = 0, opts = {}) {
  const { types } = loadShapes()
  const shape = types[typeName]
  const issues = []
  if (!shape || depth > 8) return issues
  // On a request body, an absent field is not an omission — it is the protocol. A PATCH sends the
  // fields being changed and leaves the rest alone, so reporting the rest as missing is not merely
  // noise, it is backwards. Reporting it added 10 warnings to one script and broke five budgets on
  // its first run, all of them describing correct behaviour.
  const { reportMissing = true } = opts

  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    issues.push({ severity: 'fail', message: `${where}: expected an object for ${typeName}` })
    return issues
  }

  for (const [wire, field] of Object.entries(shape)) {
    const present = Object.prototype.hasOwnProperty.call(value, wire)
    const v = value[wire]

    if (!present) {
      // Always a warning, never a failure — including for non-nullable fields. A script stubs what
      // the page reads and omits the rest, which keeps fixtures legible; treating that as drift
      // was the first version of this rule and it flagged 19 correct fixtures in one script.
      if (reportMissing) {
        issues.push({
          severity: 'warn',
          message: `${where}: missing "${wire}" (${field.kind}${field.nullable ? ', nullable' : ''})`,
        })
      }
      continue
    }

    if (v === null) {
      if (!field.nullable) {
        issues.push({
          severity: 'fail',
          message: `${where}: "${wire}" is null, but core declares it non-nullable`,
        })
      }
      continue
    }

    const check = KIND_CHECK[field.kind]
    if (check && !check(v)) {
      issues.push({
        severity: 'fail',
        message: `${where}: "${wire}" is ${Array.isArray(v) ? 'array' : typeof v}, core sends ${field.kind}`,
      })
      continue
    }

    if (field.kind === 'enum' && field.values && !field.values.includes(v)) {
      issues.push({
        severity: 'fail',
        message: `${where}: "${wire}" is "${v}", not one of ${field.values.join(', ')}`,
      })
      continue
    }

    if (field.kind === 'object' && field.type) {
      issues.push(...validate(v, field.type, `${where}.${wire}`, depth + 1, opts))
    }
    if (field.kind === 'array' && field.type) {
      v.forEach((item, i) => issues.push(...validate(item, field.type, `${where}.${wire}[${i}]`, depth + 1, opts)))
    }
  }

  for (const key of Object.keys(value)) {
    if (!Object.prototype.hasOwnProperty.call(shape, key)) {
      issues.push({ severity: 'warn', message: `${where}: "${key}" is not a field core sends` })
    }
  }

  return issues
}

/**
 * Validate one exchange: the response our stub is about to send, and the request the app just made.
 *
 * **Both directions.** `api-shapes.json` records 57 request shapes, and for a while nothing read
 * them — so a POST fixture asserting on a body that had drifted from core's `Req` DTO was exactly
 * as invisible as the response bugs this was built to catch. The app's own request body is the more
 * interesting half of the two, because it is not a fixture at all: it is what the application would
 * really send, so a mismatch there is a live client bug rather than a stale stub.
 *
 * `requestBody` is whatever `fakeApi` parsed from the outgoing request, or undefined for a GET.
 */
export function checkResponse(method, url, body, requestBody) {
  let pathname
  try {
    pathname = new URL(url).pathname
  } catch {
    return []
  }

  const endpoint = matchEndpoint(method, pathname)
  if (!endpoint) return []

  const issues = []
  if (endpoint.response && body !== undefined && body !== null) {
    issues.push(...validate(body, endpoint.response, endpoint.key))
  }
  if (endpoint.request && requestBody !== undefined && requestBody !== null) {
    issues.push(...validate(requestBody, endpoint.request, `${endpoint.key} [request]`, 0, { reportMissing: false }))
  }
  return issues
}
