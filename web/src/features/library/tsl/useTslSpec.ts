import { useCallback, useEffect, useRef, useState } from 'react'
import { compileTsl, type TslCompileResp } from '../../../api/tsl.ts'
import { emptySpec, parseSpec, serializeSpec, type TslSpec } from './tslModel.ts'

const PARSE_DEBOUNCE_MS = 400
const COMPILE_DEBOUNCE_MS = 800

export interface TslSpecStore {
  /** The JSON text — the canonical value, owned by the caller. */
  text: string
  /** Last successfully parsed spec. Drives the visual editor; survives an unparseable text. */
  spec: TslSpec
  /** Set when `text` doesn't parse. The visual editor keeps showing the last good `spec`. */
  parseError: string | null
  /** Compiler rejection text — the compiler ran and said no. */
  compileFeedback: string | null
  /**
   * The compile request itself failed — network, restart, 500. Kept apart from
   * [compileFeedback] because the two mean opposite things to a teacher: one says "your spec is
   * wrong", the other says "nobody has judged your spec". Blaming the spec for a transport
   * failure sends the teacher off to fix a spec that may be fine (audit X-018 review).
   */
  compileUnavailable: string | null
  compiling: boolean
  /** Generated scripts from the last successful compile, keyed by filename. */
  scripts: Record<string, string>
  /** Whether the spec is safe to save — parses and compiles. */
  isValid: boolean
  /** The visual editor changed something: serialise and push the text out. */
  setFromModel: (next: TslSpec) => void
  /** The JSON editor changed: take the text as authoritative and re-derive the model. */
  setFromText: (next: string) => void
}

/**
 * Two-way binding between the JSON spec text and the parsed model behind the visual editor,
 * the same loop wui ran (`TSLRootComp.updateTsl` / `updateCompose`) with the feedback cycle
 * closed structurally rather than with an `externallyChanged` flag.
 *
 * The text is the single source of truth and is owned by the caller — it is what gets saved.
 * `spec` is derived from it, except immediately after a visual edit, where the model is
 * authoritative and the text is regenerated from it.
 *
 * The loop can't run away because the re-parse effect compares the incoming text against
 * `serializeSpec(spec)`: after a visual edit those are equal by construction, so the echo of our
 * own write is skipped. After a text edit they differ, so the model is rebuilt exactly once.
 */
export function useTslSpec({
  value,
  onChange,
  solutionFileName,
}: {
  value: string
  onChange: (text: string) => void
  /**
   * Seeds requiredFiles when the text is empty — a pre-existing TSL exercise saved without a
   * tsl.json lands here, and the first visual edit serializes the empty spec out. Without the
   * real name that write carries `requiredFiles: ['lahendus.py']` beside a solution-file field
   * that says otherwise, and every submission fails file validation.
   */
  solutionFileName?: string
}): TslSpecStore {
  const parse = (text: string) =>
    text.trim() === ''
      ? { spec: emptySpec(solutionFileName), error: null }
      : parseSpec(text)

  const [parsed, setParsed] = useState(() => {
    const r = parse(value)
    return { spec: r.spec ?? emptySpec(solutionFileName), error: r.error }
  })
  const { spec, error: parseError } = parsed
  const [compileFeedback, setCompileFeedback] = useState<string | null>(null)
  const [compileUnavailable, setCompileUnavailable] = useState<string | null>(null)
  const [compiling, setCompiling] = useState(false)
  const [scripts, setScripts] = useState<Record<string, string>>({})

  // Guards the two directions against each other without a mutable "who changed it" flag.
  const specTextRef = useRef(serializeSpec(spec))

  const setFromModel = useCallback(
    (next: TslSpec) => {
      const text = serializeSpec(next)
      specTextRef.current = text
      setParsed({ spec: next, error: null })
      onChange(text)
    },
    [onChange],
  )

  const setFromText = useCallback(
    (next: string) => {
      if (next === value) return
      onChange(next)
    },
    [onChange, value],
  )

  // Text → model. Debounced: half-typed JSON is invalid most of the time, and re-deriving the
  // model on every keystroke would tear down the visual editor's field state.
  useEffect(() => {
    if (value === specTextRef.current) return
    const timer = setTimeout(() => {
      const r = parse(value)
      if (r.spec) specTextRef.current = value
      setParsed((prev) => ({ spec: r.spec ?? prev.spec, error: r.error }))
    }, PARSE_DEBOUNCE_MS)
    return () => clearTimeout(timer)
    // `parse` closes over solutionFileName only; a changed name matters for the next empty-text
    // parse, not retroactively.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value])

  // Text → compiler. Runs for both directions of edit, since either can produce a spec the
  // compiler rejects. A sequence number keeps a slow response from overwriting a newer one.
  const compileSeq = useRef(0)
  useEffect(() => {
    // Don't ask the compiler to judge something that isn't JSON yet; the parse error is the
    // more useful message and is already on screen.
    if (value.trim() !== '' && parseSpec(value).error !== null) return

    const seq = ++compileSeq.current
    const timer = setTimeout(async () => {
      // Nor the empty string (audit X-015): it stands for the empty spec, and kotlinx answers
      // it with a parser diagnostic in English. Clear any stale rejection instead.
      if (value.trim() === '') {
        if (seq === compileSeq.current) {
          setCompileFeedback(null)
          setCompileUnavailable(null)
          setScripts({})
        }
        return
      }
      setCompiling(true)
      let resp: TslCompileResp
      try {
        resp = await compileTsl(value)
      } catch (e) {
        if (seq === compileSeq.current) {
          setCompileUnavailable((e as Error).message)
          setCompileFeedback(null)
          setCompiling(false)
        }
        return
      }
      if (seq !== compileSeq.current) return
      setCompileFeedback(resp.feedback)
      setCompileUnavailable(null)
      if (resp.scripts) {
        const meta = resp.meta
        setScripts({
          ...Object.fromEntries(resp.scripts.map((s) => [s.name, s.value])),
          ...(meta
            ? {
                'meta.txt': [
                  `Compiled at: ${meta.timestamp}`,
                  `Compiler version: ${meta.compiler_version}`,
                  `Backend: ${meta.backend_id} ${meta.backend_version}`,
                ].join('\n'),
              }
            : {}),
        })
      }
      setCompiling(false)
    }, COMPILE_DEBOUNCE_MS)

    return () => clearTimeout(timer)
  }, [value])

  return {
    text: value,
    spec,
    parseError,
    compileFeedback,
    compileUnavailable,
    compiling,
    scripts,
    // An unreachable compiler also gates: validity is unknown, and saving an unjudged spec is the
    // silent-failure family this editor exists to close. The message says which situation it is.
    isValid: parseError === null && compileFeedback === null && compileUnavailable === null,
    setFromModel,
    setFromText,
  }
}
