/**
 * Turn a TSL compile rejection into something a teacher can act on (audit X-018).
 *
 * `CompileTSL.controller` returns `e.message` verbatim, so what arrives is kotlinx.serialization's
 * own developer diagnostics — "Use 'ignoreUnknownKeys = true' in 'Json {}' builder…" is advice for
 * whoever wrote the compiler, in English, followed by the teacher's whole document echoed back.
 * The only actionable parts a teacher has are *which key* and *where*, and those are extractable
 * from the two or three kotlinx shapes that actually occur. Everything else goes behind a
 * disclosure rather than being deleted: the raw text is still what a bug report needs.
 *
 * Returned `messageKey`s take a `{{name}}` param where noted. Matching is deliberately loose-first:
 * a kotlinx message that fits no known shape still gets the generic sentence, never the raw dump.
 */
export interface TslErrorSummary {
  /** i18n key for the one-sentence, teacher-voiced summary. */
  messageKey: string
  /** Interpolation params for messageKey. */
  params?: Record<string, string>
}

export function summarizeCompileError(raw: string): TslErrorSummary {
  // "Encountered an unknown key 'somethingTheUiInvented' at path: $.tests[0]"
  const unknownKey = raw.match(/unknown key '([^']+)'/i)
  if (unknownKey) {
    return { messageKey: 'tsl.errorUnknownKey', params: { name: unknownKey[1] } }
  }

  // "Field 'genericCheck' is required for type with serial name 'contains_test', but it was missing"
  const missingField = raw.match(/Field '([^']+)' is required/i)
  if (missingField) {
    return { messageKey: 'tsl.errorMissingField', params: { name: missingField[1] } }
  }

  // The plural shape interpolates List.toString(): "Fields [validateFiles, tests] are required
  // for type with serial name ..., but they were missing" — brackets, not quotes.
  const missingFields = raw.match(/Fields \[([^\]]+)\] are required/i)
  if (missingFields) {
    return { messageKey: 'tsl.errorMissingField', params: { name: missingFields[1] } }
  }

  // "Serializer for subclass 'program_imports_module_test' is not found" — a retired or mistyped
  // test type, usually in a hand-written or wui-era spec.
  const unknownType = raw.match(/subclass '([^']+)' is not found/i)
  if (unknownType) {
    return { messageKey: 'tsl.errorUnknownTestType', params: { name: unknownType[1] } }
  }

  return { messageKey: 'tsl.errorGeneric' }
}

/**
 * The parse-side counterpart. `parseSpec` fails two ways that must not share a sentence: a real
 * JSON syntax error (from `JSON.parse`, browser prose), and its own *structural* checks — which
 * fire on perfectly valid JSON, so calling those "a JSON syntax error" sends the teacher to
 * validate their JSON externally, find it valid, and be stuck. The structural strings are the
 * app's own (`tslModel.parseSpec`), so matching them is stable.
 */
export function summarizeParseError(raw: string): TslErrorSummary {
  if (raw.startsWith('TSL spec must be a JSON object')) {
    return { messageKey: 'tsl.errorNotObject' }
  }
  if (raw.startsWith('TSL spec is missing a "tests" array')) {
    return { messageKey: 'tsl.errorNoTestsArray' }
  }
  const badTest = raw.match(/^Test at index (\d+) has no "type"/)
  if (badTest) {
    return { messageKey: 'tsl.errorTestNoType', params: { index: String(Number(badTest[1]) + 1) } }
  }
  return { messageKey: 'tsl.errorNotJson' }
}
