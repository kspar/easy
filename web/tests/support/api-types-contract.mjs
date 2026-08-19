/**
 * Compares `web/src/api/types.ts` against `doc/core/api-shapes.json` — the last third of the
 * contract work (EZ-1772). The browser harness already checks *fixtures* against the shape file,
 * but a fixture only covers the fields some test happens to read. The TypeScript types cover
 * everything **the app** reads, and until this existed nothing checked them.
 *
 * ## Why this is a syntax walk and not the type checker
 *
 * `ts.createSourceFile` parses one file with no program, no `tsconfig`, no module resolution — a
 * few milliseconds. A full `ts.createProgram` would let us resolve imported types, but everything
 * this needs is declared in the file being read, and a checker that needs the whole project graph
 * to run is a checker that stops running the first time the graph breaks.
 *
 * ## Direction matters, and it is the whole substance of the check
 *
 * The naive version compares two property lists and complains about every difference. That is
 * wrong in both directions and would drown the real findings:
 *
 * - A response field core sends that the app does not read is **normal** — the app is not obliged
 *   to consume everything. Reported, never failed.
 * - A response field the app declares that core does not send is a **live bug**: every read is
 *   `undefined` while TypeScript insists it is a `string`.
 * - For a *request* body those flip. A required field the TS type omits means the client cannot
 *   construct a valid request; a field TS declares that core ignores is dead weight at best.
 *
 * So each rule below states which direction it applies to, and `severity` is decided from that —
 * not from how different the two sides look.
 *
 * ## Annotations
 *
 * One JSDoc line per interface, naming the endpoint and the path from the response root:
 *
 *   /** @endpoint GET /v2/teacher/courses -> courses[] *\/
 *   /** @requestBody PUT /v2/exercises/{exerciseId} *\/
 *
 * `->` takes a path: `courses[]` is the element type of the `courses` array, `direct_any` is an
 * object property, `exception_students[].soft_deadline` nests, and `(root)` is the response body
 * itself. Several `@endpoint` lines are allowed — a shape reached from more than one endpoint is checked
 * against each, which is how a type shared by a student and a teacher endpoint gets pinned to both.
 *
 * The endpoint string must exist in the shape file, so this also fails when an endpoint is renamed
 * or removed out from under the client — a thing no other check in the repo notices.
 *
 * ## What this does not reach, on purpose
 *
 * Only `interface` declarations. **Request bodies are mostly declared inline** in a `mutationFn`
 * signature or built as a literal at the call site, so of the ~40 mutating endpoints the shape file
 * knows about, exactly one carries a `@requestBody` line. The two request-direction rules therefore
 * guard one endpoint. That is **EZ-1779**, and it is worth stating rather than leaving to be
 * discovered: an inline type literal is not merely unchecked, it is also absent from the unannotated
 * list, so it is invisible to the gate that exists to make unchecked types visible.
 */
import ts from 'typescript'

/** Shape `kind` values that a TS `string` legitimately represents on the wire. */
const STRING_KINDS = new Set(['string', 'datetime'])

// --- TypeScript side ----------------------------------------------------------------------------

/**
 * Parses interfaces and string-literal type aliases out of one TypeScript source.
 *
 * Returns `{ interfaces, aliases }` where each interface carries its annotations and a flattened
 * description of every property: which wire kinds it admits, whether it tolerates `null`, and —
 * when it names another interface in the same file — that name, so a swapped reference can be
 * caught later.
 */
export function parseTypes(source, fileName = 'types.ts', knownAliases = new Map()) {
  const sf = ts.createSourceFile(fileName, source, ts.ScriptTarget.Latest, true)

  // String-literal unions declared as aliases (`type GraderType = 'AUTO' | 'TEACHER'`). Seeded with
  // aliases found elsewhere, because a property referencing an *imported* alias would otherwise read
  // as a nested object and collide with the shape's `enum` on kind — a false failure.
  const aliases = new Map(knownAliases)
  for (const st of sf.statements) {
    if (!ts.isTypeAliasDeclaration(st)) continue
    const values = literalUnionValues(st.type)
    if (values) aliases.set(st.name.text, values)
  }

  const interfaces = []
  for (const st of sf.statements) {
    if (!ts.isInterfaceDeclaration(st)) continue
    interfaces.push({
      name: st.name.text,
      line: sf.getLineAndCharacterOfPosition(st.name.pos).line + 1,
      tags: parseTags(source, st),
      // `interface X extends Y` — the inherited members are not in `st.members`, so without this an
      // annotated interface would be compared on half its properties and the other half would be
      // filed as reassuring `wire-property-unread` infos. Resolved in checkApiTypes(), where every
      // file has been parsed and a parent declared elsewhere can be found.
      extends: (st.heritageClauses ?? [])
        .flatMap((h) => h.types)
        .map((t) => (ts.isIdentifier(t.expression) ? t.expression.text : null))
        .filter(Boolean),
      props: st.members.filter(ts.isPropertySignature).map((m) => ({
        name: ts.isIdentifier(m.name) || ts.isStringLiteral(m.name) ? m.name.text : String(m.name.getText?.() ?? ''),
        line: sf.getLineAndCharacterOfPosition(m.name.pos).line + 1,
        ...describeType(m.type, aliases, !!m.questionToken),
      })),
    })
  }
  return { interfaces, aliases }
}

/** `'a' | 'b'` → `['a','b']`; a single `'a'` → `['a']`; anything else → null. */
function literalUnionValues(node) {
  if (!node) return null
  const parts = ts.isUnionTypeNode(node) ? node.types : [node]
  const out = []
  for (const p of parts) {
    if (ts.isLiteralTypeNode(p) && ts.isStringLiteral(p.literal)) out.push(p.literal.text)
    else return null
  }
  return out.length ? out : null
}

/**
 * Flattens a type node into the wire vocabulary of the shape file.
 *
 * `kinds` is a set because a union can admit more than one, and because `string | null` must not
 * read as "kind is a union" — the `null` is stripped into `nullable` where the shape file keeps it.
 */
function describeType(node, aliases, optional) {
  const kinds = new Set()
  let nullable = false
  let optionalOut = optional
  let ref = null
  let enumValues = null
  // What an array's elements are, as distinct from the array itself. Without this, `assets: string[]`
  // against a wire array of objects produces no finding at all: both sides agree they are arrays and
  // nothing looks inside.
  let elementKinds = null

  const visit = (n) => {
    if (!n) { kinds.add('unknown'); return }
    if (ts.isUnionTypeNode(n)) { n.types.forEach(visit); return }
    if (ts.isParenthesizedTypeNode(n)) { visit(n.type); return }
    if (n.kind === ts.SyntaxKind.NullKeyword || (ts.isLiteralTypeNode(n) && n.literal.kind === ts.SyntaxKind.NullKeyword)) {
      nullable = true
      return
    }
    if (n.kind === ts.SyntaxKind.UndefinedKeyword) { optionalOut = true; return }
    if (ts.isLiteralTypeNode(n) && ts.isStringLiteral(n.literal)) {
      kinds.add('enum')
      ;(enumValues ??= []).push(n.literal.text)
      return
    }
    if (ts.isArrayTypeNode(n)) {
      kinds.add('array')
      const el = describeType(n.elementType, aliases, false)
      elementKinds = el.kinds
      ref = el.ref
      return
    }
    if (ts.isTypeLiteralNode(n)) { kinds.add('object'); return }
    if (ts.isTypeReferenceNode(n) && ts.isIdentifier(n.typeName)) {
      const name = n.typeName.text
      if (name === 'Array') {
        kinds.add('array')
        const el = n.typeArguments?.[0] ? describeType(n.typeArguments[0], aliases, false) : null
        elementKinds = el?.kinds ?? null
        ref = el?.ref ?? null
        return
      }
      const alias = aliases.get(name)
      if (alias) { kinds.add('enum'); enumValues = [...(enumValues ?? []), ...alias]; return }
      kinds.add('object')
      ref = name
      return
    }
    switch (n.kind) {
      case ts.SyntaxKind.StringKeyword: kinds.add('string'); break
      case ts.SyntaxKind.NumberKeyword: kinds.add('number'); break
      case ts.SyntaxKind.BooleanKeyword: kinds.add('boolean'); break
      // `any`/`unknown`/`Record<…>` and friends: the type says nothing, so neither can we.
      default: kinds.add('unknown')
    }
  }
  visit(node)
  return { kinds: [...kinds], nullable, optional: optionalOut, ref, enumValues, elementKinds }
}

/**
 * Reads `@endpoint` / `@requestBody` lines out of the JSDoc immediately above a declaration.
 *
 * Done on the raw leading-comment text rather than through `ts.getJSDocTags`, because the tag
 * bodies here are free-form (`GET /v2/x -> courses[]`) and the JSDoc parser splits them on
 * whitespace in ways that lose the arrow.
 */
function parseTags(source, node) {
  const ranges = ts.getLeadingCommentRanges(source, node.pos) ?? []
  const text = ranges.map((r) => source.slice(r.pos, r.end)).join('\n')
  const tags = []
  for (const line of text.split('\n')) {
    let m = /@endpoint\s+([A-Z]+)\s+(\S+)\s*->\s*(.*?)\s*(?:\*\/)?\s*$/.exec(line)
    if (m) { tags.push({ direction: 'response', method: m[1], path: m[2], jsonPath: m[3] }); continue }
    m = /@requestBody\s+([A-Z]+)\s+(\S+)\s*(?:->\s*(.*?))?\s*(?:\*\/)?\s*$/.exec(line)
    // A request body is the root unless a path says otherwise. Defaulted here rather than in the
    // resolver, so the resolver can treat an *empty* path as the error it is.
    if (m) tags.push({ direction: 'request', method: m[1], path: m[2], jsonPath: (m[3] ?? '').trim() || '(root)' })
  }
  return tags
}

// --- shape side ---------------------------------------------------------------------------------

/**
 * Walks `jsonPath` from an endpoint's root type and returns the shape type name it lands on.
 *
 * Throws rather than returning null: a path that does not resolve means the annotation is wrong,
 * and a wrong annotation must be a failure. Silently skipping it would turn one bad line into
 * "this interface is unchecked" — the failure mode this whole programme kept finding.
 */
export function resolveShapeType(shapes, endpoint, direction, jsonPath) {
  const ep = shapes.endpoints[endpoint]
  if (!ep) throw new Error(`no such endpoint in the shape file: ${endpoint}`)
  const root = direction === 'request' ? ep.request : ep.response
  if (!root) throw new Error(`${endpoint} has no ${direction} shape`)

  let current = root
  // `(root)` rather than an empty path, so an annotation for the response body itself still reads as
  // a sentence and a truncated line cannot pass for one. An empty path is therefore an error and not
  // a synonym — otherwise `-> ` with the path lost to a bad edit would resolve to the root and check
  // the wrong type in silence, which is the one behaviour every other branch here refuses.
  if (!jsonPath.trim()) throw new Error(`${endpoint}: the path after -> is empty; write (root) to mean the body itself`)
  const segments = jsonPath.trim() === '(root)' ? [] : jsonPath.split('.').map((s) => s.trim()).filter(Boolean)
  for (const seg of segments) {
    const isArray = seg.endsWith('[]')
    const key = isArray ? seg.slice(0, -2) : seg
    const props = shapes.types[current]
    if (!props) throw new Error(`shape type ${current} is not in the shape file (walking ${jsonPath})`)
    const prop = props[key]
    if (!prop) throw new Error(`${current} has no property ${key} (walking ${jsonPath} from ${endpoint})`)
    if (isArray && prop.kind !== 'array') throw new Error(`${current}.${key} is ${prop.kind}, not an array (${jsonPath})`)
    if (!isArray && prop.kind !== 'object') throw new Error(`${current}.${key} is ${prop.kind}, not an object (${jsonPath})`)
    if (!prop.type) throw new Error(`${current}.${key} is opaque — it has no named type to walk into (${jsonPath})`)
    current = prop.type
  }
  return current
}

// --- comparison ---------------------------------------------------------------------------------

/**
 * Compares a TS property's kind set against the shape's kind.
 *
 * Returns `'ok'`, `'narrowed'`, or `'mismatch'`. `'narrowed'` is its own answer because the case it
 * names — a TS literal union over a field core declares as a bare `String` — is not a type error and
 * not obviously safe either. It is safe exactly when the server constrains the column, which the
 * shape file cannot tell us, so it needs a human decision recorded rather than a verdict guessed.
 */
function compareKinds(tsKinds, shapeKind) {
  if (tsKinds.includes('unknown')) return 'ok' // the TS type asserts nothing; nothing to contradict
  if (STRING_KINDS.has(shapeKind)) {
    if (tsKinds.some((k) => STRING_KINDS.has(k))) return 'ok'
    return tsKinds.includes('enum') ? 'narrowed' : 'mismatch'
  }
  // A shape enum is a string on the wire, so a plain `string` is loose but not wrong.
  if (shapeKind === 'enum') return tsKinds.includes('enum') || tsKinds.includes('string') ? 'ok' : 'mismatch'
  return tsKinds.includes(shapeKind) ? 'ok' : 'mismatch'
}

/**
 * The rules. Every finding names the interface, the property, and the endpoint it was checked
 * against, because a finding you cannot locate is a finding nobody acts on.
 *
 * `severity` is `'fail'` or `'info'`. Infos are counted against a baseline rather than failed —
 * they are the over-defensive and the merely-unread, and paying them down is optional. Failures
 * are client bugs.
 */
export function compare({ interfaces, shapes }) {
  const byName = new Map(interfaces.map((i) => [i.name, i]))
  const findings = []
  const emitted = new Set()

  /**
   * Deduplicated by what the finding is *about* — interface, property, wire type — and not by the
   * route that reached it. A nested type sits under several endpoints, and reporting `GradeResp.grade`
   * once per parent path would bury the distinct findings under repeats of one.
   */
  const add = (severity, rule, iface, detail, prop = null, endpoint = null, shapeName = '') => {
    const key = [rule, iface, prop, shapeName].join('|')
    if (emitted.has(key)) return
    emitted.add(key)
    findings.push({ severity, rule, interface: iface, property: prop, endpoint, detail })
  }

  // Pairs already compared. Both halves matter: the same interface is legitimately checked against
  // two different wire types (`LibraryExerciseAsset` against three), and the same wire type against
  // two interfaces. Keyed on the pair, this terminates on cycles without collapsing those cases.
  const visited = new Set()

  /**
   * Compares one interface against one wire type, then follows every reference.
   *
   * Recursion replaced an earlier rule that compared the *Kotlin type name* a reference resolved to.
   * That rule was wrong on measurement: core declares a `RespAsset` and a `GroupResp` per controller,
   * so five structurally identical wire types have five names, and four of the rule's five findings
   * were its own artefacts. Structure is what the client actually depends on — and checking it is
   * strictly stronger, because a genuinely swapped reference now fails on the property names that
   * do not line up rather than on a name comparison that happens to notice.
   */
  const walk = (iface, shapeName, direction, where, endpoint) => {
    const pair = `${iface.name}|${shapeName}|${direction}`
    if (visited.has(pair)) return
    visited.add(pair)

    const shapeProps = shapes.types[shapeName]
    if (!shapeProps) {
      add('fail', 'shape-type-missing', iface.name, `${where}: the shape file does not describe ${shapeName}`, null, endpoint, shapeName)
      return
    }
    // A parent we could not find means we are comparing half an interface, and the missing half
    // would be reported as reassuring `wire-property-unread` infos. Fail loudly instead.
    if (iface.unresolvedExtends?.length) {
      add('fail', 'extends-unresolved', iface.name,
        `${where}: extends ${iface.unresolvedExtends.join(', ')}, which is not declared in web/src/api — its inherited properties are not being checked`,
        null, endpoint, shapeName)
    }
    const isResponse = direction === 'response'

    for (const p of iface.props) {
      const sp = shapeProps[p.name]

      // Rule 1. TS declares a field the endpoint does not carry.
      // Response: every read of it is `undefined` while the type promises otherwise — a bug.
      // Request: core ignores it, which is dead weight rather than a crash, but still a lie.
      if (!sp) {
        add('fail', 'ts-property-not-sent', iface.name,
          `${where} has no \`${p.name}\`${isResponse ? ' — every read of it is undefined' : ' — core ignores it'}`,
          p.name, endpoint, shapeName)
        continue
      }

      // Rule 2. Kinds must agree. A number read as a string is the classic silent one: it renders,
      // sorts wrong, and nothing throws.
      const verdict = compareKinds(p.kinds, sp.kind)
      if (verdict === 'mismatch') {
        add('fail', 'kind-mismatch', iface.name,
          `${where}: \`${p.name}\` is ${sp.kind} on the wire, ${p.kinds.join('|')} in TypeScript`,
          p.name, endpoint, shapeName)
      } else if (verdict === 'narrowed') {
        add('fail', 'ts-narrows-wire-string', iface.name,
          `${where}: \`${p.name}\` is an unconstrained string on the wire, narrowed to ${p.enumValues?.map((v) => `'${v}'`).join(' | ')} in TypeScript — safe only if core validates it`,
          p.name, endpoint, shapeName)
      }

      // Rule 2b. And what is *inside* an array. Both sides agreeing they are arrays is not agreement:
      // `assets: string[]` against a wire array of objects passes every other rule here.
      if (sp.kind === 'array' && sp.type && p.elementKinds && !p.elementKinds.includes('object') && !p.elementKinds.includes('unknown')) {
        add('fail', 'array-element-mismatch', iface.name,
          `${where}: \`${p.name}\` is an array of ${sp.type} on the wire, of ${p.elementKinds.join('|')} in TypeScript`,
          p.name, endpoint, shapeName)
      }

      // Rule 3. Nullability, in the dangerous direction for each — and they are different
      // directions. A response the client refuses to treat as nullable crashes on the first null; a
      // *request* field the client may send as null, or omit, is one core will reject.
      if (isResponse && sp.nullable && !p.nullable && !p.optional) {
        add('fail', 'nullable-not-declared', iface.name,
          `${where}: core can send \`${p.name}\` as null; TypeScript says it cannot be`,
          p.name, endpoint, shapeName)
      }
      if (isResponse && !sp.nullable && (p.nullable || p.optional)) {
        add('info', 'over-defensive-nullable', iface.name,
          `${where}: \`${p.name}\` is never null on the wire but the type allows it`,
          p.name, endpoint, shapeName)
      }
      if (!isResponse && !sp.nullable && (p.nullable || p.optional)) {
        add('fail', 'request-field-optional-but-required', iface.name,
          `${where}: \`${p.name}\` is required and non-nullable on the wire, and the type lets a caller ${p.optional ? 'omit it' : 'send null'}`,
          p.name, endpoint, shapeName)
      }

      // Rule 4. Enum values, both ways for a response.
      // A value core sends that the type does not admit is the one that bites: the switch statement
      // over it has no branch, so the UI renders nothing for a state that exists.
      if (sp.kind === 'enum' && sp.values && p.enumValues) {
        const wire = new Set(sp.values)
        const declared = new Set(p.enumValues)
        const missing = sp.values.filter((v) => !declared.has(v))
        const extra = p.enumValues.filter((v) => !wire.has(v))
        if (isResponse && missing.length) {
          add('fail', 'enum-value-unhandled', iface.name,
            `${where}: \`${p.name}\` can be ${missing.map((v) => `'${v}'`).join(', ')} on the wire, and the type does not admit it`,
            p.name, endpoint, shapeName)
        }
        if (extra.length) {
          add('fail', 'enum-value-not-real', iface.name,
            `${where}: \`${p.name}\` declares ${extra.map((v) => `'${v}'`).join(', ')}, which core's enum does not have`,
            p.name, endpoint, shapeName)
        }
      }

      // Rule 5. Follow the reference. An inline object literal has no name to follow, and an opaque
      // wire property has no named type to follow into; both stop here, which is why a nested shape
      // is better spelled as its own exported interface.
      if (p.ref && sp.type && byName.has(p.ref)) {
        walk(byName.get(p.ref), sp.type, direction, `${where}.${p.name}`, endpoint)
      }
    }

    // Rule 6, request only. A required field the type cannot express is a request the client cannot
    // construct. For a response the same gap is just a field the app does not read.
    for (const [name, sp] of Object.entries(shapeProps)) {
      if (iface.props.some((p) => p.name === name)) continue
      if (isResponse) {
        add('info', 'wire-property-unread', iface.name, `${where} carries \`${name}\`, which the app does not read`, name, endpoint, shapeName)
      } else if (!sp.nullable) {
        add('fail', 'required-request-field-missing', iface.name,
          `${where} requires \`${name}\`, which the type does not have`, name, endpoint, shapeName)
      }
    }
  }

  for (const iface of interfaces) {
    for (const tag of iface.tags) {
      const endpoint = `${tag.method} ${tag.path}`
      let shapeName
      try {
        shapeName = resolveShapeType(shapes, endpoint, tag.direction, tag.jsonPath)
      } catch (e) {
        add('fail', 'annotation-unresolvable', iface.name, e.message, null, endpoint, tag.jsonPath)
        continue
      }
      walk(iface, shapeName, tag.direction, `${endpoint} -> ${tag.jsonPath} = ${shapeName}`, endpoint)
    }
  }
  return findings
}

/**
 * Entry point: parse, compare, and split findings from the unannotated-interface list.
 *
 * `unannotated` is deliberately not a failure. Annotating all of `src/api/` at once was not worth
 * blocking on, but an interface that is *silently* unchecked is exactly what this replaces — so it
 * goes in a baseline that can only shrink, the same shape as `contract-baseline.json` and the a11y
 * baseline.
 */
export function checkApiTypes({ sources, shapes }) {
  // Two passes: collect every string-literal alias first, then parse for real with the full table.
  // One pass would resolve `GraderType` only in the file that declares it.
  const aliases = new Map()
  for (const [fileName, source] of Object.entries(sources)) {
    for (const [k, v] of parseTypes(source, fileName).aliases) aliases.set(k, v)
  }
  const parsed = []
  for (const [fileName, source] of Object.entries(sources)) {
    for (const i of parseTypes(source, fileName, aliases).interfaces) parsed.push({ ...i, file: fileName })
  }
  const interfaces = flattenHeritage(parsed)

  // Every interface goes to compare(), not just the annotated ones: the walk starts at annotations
  // but has to be able to follow a reference into an interface that carries none.
  const findings = compare({ interfaces, shapes })
  return {
    findings,
    failures: findings.filter((f) => f.severity === 'fail'),
    infos: findings.filter((f) => f.severity === 'info'),
    annotated: interfaces.filter((i) => i.tags.length).map((i) => i.name),
    unannotated: interfaces.filter((i) => !i.tags.length).map((i) => `${i.file}:${i.name}`),
  }
}

/**
 * Folds inherited properties into each interface, so `interface AdminSystemMessage extends
 * SystemMessage` is compared on all of its properties rather than the ones it adds.
 *
 * A child's own declaration wins over the parent's, matching TypeScript. A parent that is not
 * declared anywhere in the sources we were given is recorded on the interface rather than ignored —
 * comparing an interface we only half understand and reporting the other half as "the app does not
 * read this" is the reassuring-direction failure, and this whole module is built to refuse it.
 */
export function flattenHeritage(parsed) {
  const byName = new Map(parsed.map((i) => [i.name, i]))

  const resolve = (iface, seen = new Set()) => {
    if (iface.resolved) return iface
    // A cycle is not expressible in valid TypeScript, but this runs on files that may not compile.
    if (seen.has(iface.name)) return { ...iface, resolved: true, unresolvedExtends: [] }
    seen.add(iface.name)

    const own = new Map(iface.props.map((p) => [p.name, p]))
    const unresolved = []
    const inherited = []
    for (const parentName of iface.extends ?? []) {
      const parent = byName.get(parentName)
      if (!parent) { unresolved.push(parentName); continue }
      for (const p of resolve(parent, seen).props) if (!own.has(p.name)) inherited.push(p)
    }
    const out = { ...iface, props: [...iface.props, ...inherited], unresolvedExtends: unresolved, resolved: true }
    byName.set(iface.name, out)
    return out
  }
  return parsed.map((i) => resolve(i))
}

/** `rule|interface|property` — stable across a reworded message or a re-routed endpoint. */
export function fingerprint(f) {
  return [f.rule, f.interface, f.property ?? ''].join('|')
}

/**
 * Reconciles failures and the unannotated list against the committed baseline.
 *
 * The same contract as the a11y baseline, for the same reason: a waiver without a note and an issue
 * is indistinguishable from nobody having looked, so it is rejected at load rather than trusted. And
 * a waiver that no longer fires is a failure, not a tidy-up — otherwise the file accumulates
 * permissions for problems that were fixed years ago and quietly re-permits them when they return.
 */
export function reconcile({ failures, unannotated, baseline }) {
  for (const w of baseline.waivers ?? []) {
    if (!w.fingerprint || !w.note || !w.issue) {
      throw new Error(`api-types baseline: every waiver needs a fingerprint, a note and an issue; got ${JSON.stringify(w)}`)
    }
  }
  const waived = new Map((baseline.waivers ?? []).map((w) => [w.fingerprint, w]))
  const seen = new Set()

  const unwaived = []
  for (const f of failures) {
    const fp = fingerprint(f)
    if (waived.has(fp)) { seen.add(fp); continue }
    unwaived.push(f)
  }
  const staleWaivers = [...waived.keys()].filter((fp) => !seen.has(fp))

  const listed = new Set(baseline.unannotated ?? [])
  const nowUnannotated = new Set(unannotated)
  return {
    unwaived,
    staleWaivers,
    // New unannotated interfaces fail: an interface nobody enrolled is exactly the silently
    // unchecked state this replaces.
    newlyUnannotated: unannotated.filter((n) => !listed.has(n)),
    // And one that left the list — annotated, renamed or deleted — must be struck from it, so the
    // list can only shrink.
    staleUnannotated: [...listed].filter((n) => !nowUnannotated.has(n)),
  }
}

/** One line per finding, for a test failure message that says what to do. */
export function describeFindings(findings) {
  return findings.map((f) => `  [${f.rule}] ${f.interface}${f.property ? `.${f.property}` : ''}: ${f.detail}`).join('\n')
}
