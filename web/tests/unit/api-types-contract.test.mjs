/**
 * `web/src/api/types.ts` against `doc/core/api-shapes.json` — EZ-1772, the last third of EZ-1770.
 *
 * Two halves, and the second is not optional.
 *
 * The first runs the checker over the real files and expects nothing. The second feeds it a
 * deliberately broken client for **every rule it claims to have**, and fails if the rule stays
 * quiet. That split exists because this programme found seven separate detectors that were
 * structurally incapable of reporting the thing they watched for — and every one of them was
 * green. A checker whose only test is "the real code passes" is indistinguishable from a checker
 * that cannot fail, and the first version of this one proves the point: it opened with seven
 * findings, of which four were its own artefacts.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join, relative } from 'node:path'
import {
  checkApiTypes,
  compare,
  parseTypes,
  resolveShapeType,
  reconcile,
  fingerprint,
  flattenHeritage,
  describeFindings,
} from '../support/api-types-contract.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const repo = join(here, '..', '..', '..')
const apiDir = join(repo, 'web', 'src', 'api')

const shapes = JSON.parse(readFileSync(join(repo, 'doc', 'core', 'api-shapes.json'), 'utf8'))
const baseline = JSON.parse(readFileSync(join(here, '..', 'api-types-baseline.json'), 'utf8'))
/**
 * Every TypeScript source under `src/api`, recursively and including `.tsx`.
 *
 * A flat `.ts`-only listing would have let an API type in a subdirectory or a `.tsx` file escape even
 * the unannotated list — invisible to the gate that exists to make unchecked types visible.
 */
function collectSources(dir) {
  const out = {}
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) Object.assign(out, collectSources(full))
    else if (/\.tsx?$/.test(entry.name)) out[relative(apiDir, full)] = readFileSync(full, 'utf8')
  }
  return out
}
const sources = collectSources(apiDir)

const result = checkApiTypes({ sources, shapes })
const reconciled = reconcile({ failures: result.failures, unannotated: result.unannotated, baseline })

describe('web/src/api against doc/core/api-shapes.json', () => {
  it('finds the annotated interfaces at all', () => {
    // A scan that silently matches nothing is the worst outcome available: every other assertion
    // here passes vacuously. 40 is a floor well under the real count, not a target.
    expect(result.annotated.length).toBeGreaterThan(40)
    expect(Object.keys(shapes.endpoints).length).toBeGreaterThan(100)
  })

  it('has no unwaived contract failures', () => {
    expect(reconciled.unwaived, `\n${describeFindings(reconciled.unwaived)}\n`).toEqual([])
  })

  it('has no waivers that no longer fire', () => {
    // Delete these lines from the baseline — the fix landed, and leaving the permission behind
    // silently re-permits the problem if it comes back.
    expect(reconciled.staleWaivers).toEqual([])
  })

  it('has no newly unannotated interface', () => {
    // Add an `@endpoint` line, or add the name to the baseline's `unannotated` list with the rest.
    expect(reconciled.newlyUnannotated).toEqual([])
  })

  it('has no baseline entry for an interface that is now annotated or gone', () => {
    expect(reconciled.staleUnannotated).toEqual([])
  })

  it('emits infos only under the two rules that are declared informational', () => {
    // Deliberately not a count or a threshold. `over-defensive-nullable` and `wire-property-unread`
    // are facts about the client rather than defects, and gating on how many there are would only
    // discourage adding fields to core. What *is* worth asserting is that nothing else arrives as an
    // info: a rule mis-tagged `info` is a rule that has been switched off without anyone saying so.
    for (const f of result.infos) {
      expect(['over-defensive-nullable', 'wire-property-unread'], f.detail).toContain(f.rule)
    }
  })
})

// --- the rules, each given a case it must catch -------------------------------------------------

/** Builds a one-endpoint shape file around a single response or request type. */
function shapeFile(props, { direction = 'response', extra = {} } = {}) {
  return {
    endpoints: { 'GET /v2/thing': { [direction]: 'core.Thing' } },
    types: { 'core.Thing': props, ...extra },
  }
}

/** Parses one TS source and compares it against a synthetic shape file. */
function check(source, shapes) {
  const { interfaces } = parseTypes(source)
  return compare({ interfaces, shapes })
}

const rules = (findings) => findings.map((f) => f.rule)

describe('each rule fires on a client that breaks it', () => {
  it('ts-property-not-sent: the type declares a field the endpoint does not carry', () => {
    const f = check(
      `/** @endpoint GET /v2/thing -> (root) */
       export interface Thing { id: string; nope: string }`,
      shapeFile({ id: { kind: 'string' } }),
    )
    expect(rules(f)).toContain('ts-property-not-sent')
    expect(f.find((x) => x.rule === 'ts-property-not-sent').property).toBe('nope')
  })

  it('kind-mismatch: a wire number read as a string', () => {
    const f = check(
      `/** @endpoint GET /v2/thing -> (root) */
       export interface Thing { grade: string }`,
      shapeFile({ grade: { kind: 'number' } }),
    )
    expect(rules(f)).toContain('kind-mismatch')
  })

  it('kind-mismatch: an array read as a single object', () => {
    const f = check(
      `/** @endpoint GET /v2/thing -> (root) */
       export interface Thing { groups: Group }
       export interface Group { id: string }`,
      shapeFile({ groups: { kind: 'array', type: 'core.Group' } }, { extra: { 'core.Group': { id: { kind: 'string' } } } }),
    )
    expect(rules(f)).toContain('kind-mismatch')
  })

  it('accepts a wire datetime as a TS string, because that is what JSON carries', () => {
    const f = check(
      `/** @endpoint GET /v2/thing -> (root) */
       export interface Thing { created_at: string }`,
      shapeFile({ created_at: { kind: 'datetime' } }),
    )
    expect(f).toEqual([])
  })

  it('ts-narrows-wire-string: a literal union over an unconstrained wire string', () => {
    const f = check(
      `/** @endpoint GET /v2/thing -> (root) */
       export interface Thing { type: 'comment' | 'suggestion' }`,
      shapeFile({ type: { kind: 'string' } }),
    )
    expect(rules(f)).toContain('ts-narrows-wire-string')
    // Separate from kind-mismatch on purpose: it is waivable, and a real kind mismatch is not.
    expect(rules(f)).not.toContain('kind-mismatch')
  })

  it('nullable-not-declared: core can send null and the type says it cannot', () => {
    const f = check(
      `/** @endpoint GET /v2/thing -> (root) */
       export interface Thing { alias: string }`,
      shapeFile({ alias: { kind: 'string', nullable: true } }),
    )
    expect(rules(f)).toContain('nullable-not-declared')
    expect(f.find((x) => x.rule === 'nullable-not-declared').severity).toBe('fail')
  })

  it('counts an optional property as tolerating the null, since a read of it is checked either way', () => {
    const f = check(
      `/** @endpoint GET /v2/thing -> (root) */
       export interface Thing { alias?: string }`,
      shapeFile({ alias: { kind: 'string', nullable: true } }),
    )
    expect(rules(f)).not.toContain('nullable-not-declared')
  })

  it('over-defensive-nullable is an info, not a failure', () => {
    const f = check(
      `/** @endpoint GET /v2/thing -> (root) */
       export interface Thing { title: string | null }`,
      shapeFile({ title: { kind: 'string' } }),
    )
    const found = f.find((x) => x.rule === 'over-defensive-nullable')
    expect(found).toBeDefined()
    expect(found.severity).toBe('info')
  })

  it('enum-value-unhandled: core sends a value the union does not admit', () => {
    const f = check(
      `/** @endpoint GET /v2/thing -> (root) */
       export interface Thing { status: 'STARTED' | 'COMPLETED' }`,
      shapeFile({ status: { kind: 'enum', values: ['STARTED', 'COMPLETED', 'UNGRADED'] } }),
    )
    const found = f.find((x) => x.rule === 'enum-value-unhandled')
    expect(found).toBeDefined()
    expect(found.detail).toContain('UNGRADED')
  })

  it('enum-value-not-real: the union declares a value core does not have', () => {
    const f = check(
      `/** @endpoint GET /v2/thing -> (root) */
       export interface Thing { status: 'STARTED' | 'ABANDONED' }`,
      shapeFile({ status: { kind: 'enum', values: ['STARTED'] } }),
    )
    expect(f.find((x) => x.rule === 'enum-value-not-real').detail).toContain('ABANDONED')
  })

  it('resolves an enum through a type alias, so an aliased union is checked like an inline one', () => {
    const f = check(
      `export type Status = 'STARTED' | 'ABANDONED'
       /** @endpoint GET /v2/thing -> (root) */
       export interface Thing { status: Status }`,
      shapeFile({ status: { kind: 'enum', values: ['STARTED'] } }),
    )
    expect(rules(f)).toContain('enum-value-not-real')
  })

  it('required-request-field-missing: a request body the client cannot construct', () => {
    const f = check(
      `/** @requestBody GET /v2/thing */
       export interface Thing { title: string }`,
      shapeFile({ title: { kind: 'string' }, threshold: { kind: 'number' } }, { direction: 'request' }),
    )
    const found = f.find((x) => x.rule === 'required-request-field-missing')
    expect(found).toBeDefined()
    expect(found.property).toBe('threshold')
  })

  it('does not fail a request body for omitting a nullable field', () => {
    const f = check(
      `/** @requestBody GET /v2/thing */
       export interface Thing { title: string }`,
      shapeFile({ title: { kind: 'string' }, alias: { kind: 'string', nullable: true } }, { direction: 'request' }),
    )
    expect(f.filter((x) => x.severity === 'fail')).toEqual([])
  })

  it('array-element-mismatch: an array of objects on the wire, of strings in TypeScript', () => {
    // Both sides agree it is an array, so every other rule here passes. This is the one that looks.
    const f = check(
      `/** @endpoint GET /v2/thing -> (root) */
       export interface Thing { assets: string[] }`,
      shapeFile({ assets: { kind: 'array', type: 'core.Asset' } }, { extra: { 'core.Asset': { file_name: { kind: 'string' } } } }),
    )
    expect(rules(f)).toContain('array-element-mismatch')
  })

  it('accepts an array of inline object literals, which has no name to follow but the right kind', () => {
    const f = check(
      `/** @endpoint GET /v2/thing -> (root) */
       export interface Thing { executors: { id: string; name: string }[] }`,
      shapeFile({ executors: { kind: 'array', type: 'core.Ex' } }, { extra: { 'core.Ex': { id: { kind: 'string' } } } }),
    )
    expect(f.filter((x) => x.severity === 'fail')).toEqual([])
  })

  it('request-field-optional-but-required: the type lets a caller omit a required field', () => {
    const f = check(
      `/** @requestBody GET /v2/thing */
       export interface Thing { title?: string }`,
      shapeFile({ title: { kind: 'string' } }, { direction: 'request' }),
    )
    expect(rules(f)).toContain('request-field-optional-but-required')
  })

  it('request-field-optional-but-required: or send null where core requires a value', () => {
    const f = check(
      `/** @requestBody GET /v2/thing */
       export interface Thing { title: string | null }`,
      shapeFile({ title: { kind: 'string' } }, { direction: 'request' }),
    )
    const found = f.find((x) => x.rule === 'request-field-optional-but-required')
    expect(found.detail).toContain('send null')
    // The mirror rule must NOT fire here: over-defensive is a response notion.
    expect(rules(f)).not.toContain('over-defensive-nullable')
  })

  it('does not fail a response for carrying a field the app ignores', () => {
    const f = check(
      `/** @endpoint GET /v2/thing -> (root) */
       export interface Thing { title: string }`,
      shapeFile({ title: { kind: 'string' }, extra: { kind: 'number' } }),
    )
    expect(f.filter((x) => x.severity === 'fail')).toEqual([])
    expect(rules(f)).toContain('wire-property-unread')
  })
})

describe('the recursive walk', () => {
  const nestedShapes = shapeFile(
    { groups: { kind: 'array', type: 'core.Group' } },
    { extra: { 'core.Group': { id: { kind: 'string' }, name: { kind: 'string' } } } },
  )

  it('checks a nested interface that carries no annotation of its own', () => {
    const f = check(
      `/** @endpoint GET /v2/thing -> (root) */
       export interface Thing { groups: Group[] }
       export interface Group { id: string; name: number }`,
      nestedShapes,
    )
    expect(rules(f)).toContain('kind-mismatch')
    expect(f.find((x) => x.rule === 'kind-mismatch').interface).toBe('Group')
  })

  it('catches a swapped reference on structure, which is what the client depends on', () => {
    // The case an earlier version of this checker compared Kotlin type *names* to catch. Names were
    // the wrong test — core declares a `GroupResp` per controller, so identical wire types have
    // different names and four of five findings were noise. Structure catches the real swap.
    const f = check(
      `/** @endpoint GET /v2/thing -> (root) */
       export interface Thing { groups: Teacher[] }
       export interface Teacher { id: string; given_name: string }`,
      nestedShapes,
    )
    expect(f.find((x) => x.rule === 'ts-property-not-sent').property).toBe('given_name')
  })

  it('accepts two structurally identical wire types under one TS interface', () => {
    const f = check(
      `/** @endpoint GET /v2/thing -> (root) */
       export interface Thing { a: Asset[]; b: Asset[] }
       export interface Asset { file_name: string }`,
      shapeFile(
        { a: { kind: 'array', type: 'core.A.Asset' }, b: { kind: 'array', type: 'core.B.Asset' } },
        { extra: { 'core.A.Asset': { file_name: { kind: 'string' } }, 'core.B.Asset': { file_name: { kind: 'string' } } } },
      ),
    )
    expect(f.filter((x) => x.severity === 'fail')).toEqual([])
  })

  it('terminates on a self-referential type', () => {
    const f = check(
      `/** @endpoint GET /v2/thing -> (root) */
       export interface Thing { child: Thing | null; id: string }`,
      shapeFile({ child: { kind: 'object', nullable: true, type: 'core.Thing' }, id: { kind: 'string' } }),
    )
    expect(f.filter((x) => x.severity === 'fail')).toEqual([])
  })

  it('reports a nested finding once, not once per route that reaches it', () => {
    const f = check(
      `/** @endpoint GET /v2/thing -> (root) */
       export interface Thing { a: Group[]; b: Group[] }
       export interface Group { bad: string }`,
      shapeFile(
        { a: { kind: 'array', type: 'core.Group' }, b: { kind: 'array', type: 'core.Group' } },
        { extra: { 'core.Group': { id: { kind: 'string' } } } },
      ),
    )
    expect(f.filter((x) => x.rule === 'ts-property-not-sent')).toHaveLength(1)
  })
})

describe('inherited properties', () => {
  it('folds a parent interface in, so the child is compared on all of its properties', () => {
    const parsed = parseTypes(
      `export interface Base { id: string }
       /** @endpoint GET /v2/thing -> (root) */
       export interface Thing extends Base { extra: number }`,
    ).interfaces
    const iface = flattenHeritage(parsed).find((i) => i.name === 'Thing')
    expect(iface.props.map((p) => p.name).sort()).toEqual(['extra', 'id'])
    expect(iface.unresolvedExtends).toEqual([])
  })

  it("lets the child's own declaration win, as TypeScript does", () => {
    const parsed = parseTypes(
      `export interface Base { grade: string }
       export interface Thing extends Base { grade: number }`,
    ).interfaces
    const iface = flattenHeritage(parsed).find((i) => i.name === 'Thing')
    expect(iface.props.filter((p) => p.name === 'grade')).toHaveLength(1)
    expect(iface.props.find((p) => p.name === 'grade').kinds).toEqual(['number'])
  })

  it('fails rather than half-checking when the parent is not among the sources', () => {
    // The reassuring-direction failure this guards: without it, the inherited properties are simply
    // absent from the TS side and come back as `wire-property-unread` infos — "the app does not read
    // these", when in fact nobody looked.
    const parsed = parseTypes(
      `/** @endpoint GET /v2/thing -> (root) */
       export interface Thing extends Elsewhere { id: string }`,
    ).interfaces
    const f = compare({ interfaces: flattenHeritage(parsed), shapes: shapeFile({ id: { kind: 'string' } }) })
    expect(rules(f)).toContain('extends-unresolved')
  })

  it('terminates on a heritage cycle, which valid TypeScript cannot express but a broken file can', () => {
    const parsed = parseTypes(
      `export interface A extends B { a: string }
       export interface B extends A { b: string }`,
    ).interfaces
    expect(() => flattenHeritage(parsed)).not.toThrow()
  })
})

describe('annotations', () => {
  it('walks an array path to its element type', () => {
    expect(resolveShapeType(
      { endpoints: { 'GET /v2/x': { response: 'R' } }, types: { R: { courses: { kind: 'array', type: 'C' } }, C: {} } },
      'GET /v2/x', 'response', 'courses[]',
    )).toBe('C')
  })

  it('walks a nested object path', () => {
    expect(resolveShapeType(
      {
        endpoints: { 'GET /v2/x': { response: 'R' } },
        types: { R: { rows: { kind: 'array', type: 'Row' } }, Row: { grade: { kind: 'object', type: 'G' } }, G: {} },
      },
      'GET /v2/x', 'response', 'rows[].grade',
    )).toBe('G')
  })

  it('fails on an endpoint the shape file does not have, so a renamed route cannot pass', () => {
    const f = check(
      `/** @endpoint GET /v2/gone -> (root) */
       export interface Thing { id: string }`,
      shapeFile({ id: { kind: 'string' } }),
    )
    expect(rules(f)).toContain('annotation-unresolvable')
  })

  it('fails on a path that does not resolve, rather than skipping the interface', () => {
    // The important half. Treating an unresolvable path as "nothing to check here" would turn one
    // typo into an interface nobody is checking, which is the state this test exists to end.
    const f = check(
      `/** @endpoint GET /v2/thing -> nosuchkey[] */
       export interface Thing { id: string }`,
      shapeFile({ id: { kind: 'string' } }),
    )
    expect(rules(f)).toEqual(['annotation-unresolvable'])
  })

  it('fails on an empty path rather than treating it as (root)', () => {
    // `-> ` with the path lost to a bad edit must not resolve to the response root and quietly check
    // the wrong type — the one behaviour every other branch of the resolver refuses.
    const f = check(
      `/** @endpoint GET /v2/thing -> */
       export interface Thing { id: string }`,
      shapeFile({ id: { kind: 'string' } }),
    )
    expect(rules(f)).toEqual(['annotation-unresolvable'])
    expect(f[0].detail).toContain('(root)')
  })

  it('fails when a path walks into an opaque wire property', () => {
    const f = check(
      `/** @endpoint GET /v2/thing -> blob */
       export interface Thing { id: string }`,
      shapeFile({ blob: { kind: 'object' } }),
    )
    expect(f[0].detail).toContain('opaque')
  })

  it('fails when the direction has no shape, rather than passing silently', () => {
    const f = check(
      `/** @requestBody GET /v2/thing */
       export interface Thing { id: string }`,
      shapeFile({ id: { kind: 'string' } }),
    )
    expect(f[0].detail).toContain('no request shape')
  })

  it('checks one interface against every endpoint it is annotated with', () => {
    const f = check(
      `/** @endpoint GET /v2/a -> (root) */
       /** @endpoint GET /v2/b -> (root) */
       export interface Thing { id: string }`,
      {
        endpoints: { 'GET /v2/a': { response: 'A' }, 'GET /v2/b': { response: 'B' } },
        types: { A: { id: { kind: 'string' } }, B: { id: { kind: 'number' } } },
      },
    )
    expect(rules(f)).toContain('kind-mismatch')
  })

  it('reads an annotation that sits alongside other JSDoc', () => {
    const { interfaces } = parseTypes(
      `/** Body of the update — the whole exercise, not a patch. */
       /** @requestBody PUT /v2/exercises/{exerciseId} */
       export interface Thing { id: string }`,
    )
    expect(interfaces[0].tags).toEqual([
      { direction: 'request', method: 'PUT', path: '/v2/exercises/{exerciseId}', jsonPath: '(root)' },
    ])
  })
})

describe('the baseline', () => {
  const failure = { severity: 'fail', rule: 'ts-narrows-wire-string', interface: 'Thing', property: 'type', detail: '' }

  it('rejects a waiver with no note or issue at load', () => {
    expect(() => reconcile({ failures: [], unannotated: [], baseline: { waivers: [{ fingerprint: 'a|b|c' }] } }))
      .toThrow(/note and an issue/)
    expect(() => reconcile({
      failures: [], unannotated: [],
      baseline: { waivers: [{ fingerprint: 'a|b|c', note: 'because' }] },
    })).toThrow(/note and an issue/)
  })

  it('waives a failure it names', () => {
    const r = reconcile({
      failures: [failure], unannotated: [],
      baseline: { waivers: [{ fingerprint: fingerprint(failure), note: 'core does not validate it', issue: 'EZ-1' }] },
    })
    expect(r.unwaived).toEqual([])
    expect(r.staleWaivers).toEqual([])
  })

  it('reports a waiver that no longer fires', () => {
    const r = reconcile({
      failures: [], unannotated: [],
      baseline: { waivers: [{ fingerprint: 'gone|Thing|x', note: 'n', issue: 'EZ-1' }] },
    })
    expect(r.staleWaivers).toEqual(['gone|Thing|x'])
  })

  it('does not let a waiver cover a different property of the same rule', () => {
    const other = { ...failure, property: 'other' }
    const r = reconcile({
      failures: [other], unannotated: [],
      baseline: { waivers: [{ fingerprint: fingerprint(failure), note: 'n', issue: 'EZ-1' }] },
    })
    expect(r.unwaived).toHaveLength(1)
    expect(r.staleWaivers).toHaveLength(1)
  })

  it('fails a newly unannotated interface and a stale listing, in both directions', () => {
    const r = reconcile({
      failures: [], unannotated: ['a.ts:New', 'a.ts:Known'],
      baseline: { unannotated: ['a.ts:Known', 'a.ts:Departed'] },
    })
    expect(r.newlyUnannotated).toEqual(['a.ts:New'])
    expect(r.staleUnannotated).toEqual(['a.ts:Departed'])
  })
})
