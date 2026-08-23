import type { SystemMessage } from '../api/messages.ts'

/**
 * The key a dismissed system message is remembered under (EZ-1790).
 *
 * **Content, not identity.** This used to be the message's `id`, which comes from a `bigserial` —
 * so a dismissal was a claim about a row number rather than about a message. Dev's database is
 * periodically restored from an anonymised production dump, so "message 1" recorded in a browser
 * and "message 1" in the database need not be the same message, and the client cannot tell. A
 * dismissal made weeks ago against a since-deleted message silently suppressed a brand-new one,
 * which is exactly how this was found: a correctly configured message that simply never appeared.
 *
 * Keying on content makes the two questions the same question. Two genuinely identical messages are
 * the same announcement, so sharing a key is right. An *edited* message is new information, so
 * getting a new key and reappearing is also right — that is a feature, not a side effect.
 *
 * ### Why a home-made hash is the right tool
 *
 * This is a cache key for a handful of rows in one browser's `localStorage`. It is not a security
 * boundary, nothing is authenticated by it, and nobody can gain anything by colliding with it. So
 * `crypto.subtle.digest` — the only hash the platform offers — buys nothing and costs an async
 * boundary in a render path that has no other reason to be async.
 *
 * FNV-1a twice with different offset bases, giving 64 bits. One pass would very probably do: with 32
 * bits and fifty messages the chance of any collision is about three in ten million. But a collision
 * here means one dismissed message silently suppressing a different one, which is the precise bug
 * this function exists to remove, so paying three lines to make it negligible is the better trade.
 */

/** Format marker, so a future scheme can coexist and so legacy numeric ids stay recognisable. */
const PREFIX = 'sm1_'

/**
 * NUL as the field separator.
 *
 * A message body can contain anything a person types, newlines included, so a printable separator
 * would let two different sets of fields render as the same string. NUL cannot appear in the JSON
 * these values arrive in.
 */
const SEP = '\u0000'

function fnv1a(input: string, basis: number): number {
  let h = basis
  for (let i = 0; i < input.length; i++) {
    h ^= input.charCodeAt(i)
    // The FNV prime, via Math.imul so it stays 32-bit rather than drifting into float territory.
    h = Math.imul(h, 0x01000193)
  }
  // Unsigned, so the base36 form has no leading minus.
  return h >>> 0
}

/** The stable key for one message's content. */
export function dismissalKey(
  msg: Pick<SystemMessage, 'message' | 'severity' | 'link_url' | 'link_label'>,
): string {
  const material = [msg.message, msg.severity, msg.link_url ?? '', msg.link_label ?? ''].join(SEP)
  const a = fnv1a(material, 0x811c9dc5).toString(36)
  const b = fnv1a(material, 0x1000193).toString(36)
  return `${PREFIX}${a}${b}`
}

/**
 * Drop anything that is not a key in the current format.
 *
 * Existing storage holds bare row ids — `["1","2"]` — which can never match a content hash, so
 * every previously dismissed message reappears once after this ships. That is the intended
 * behaviour and it is what un-sticks the message that prompted EZ-1790, with nobody having to open
 * devtools.
 *
 * Pruned on read rather than rewritten immediately: this runs inside a `useState` initialiser, and
 * writing to storage from one is a side effect in render. The pruned list is what gets persisted the
 * next time anything is dismissed, which is soon enough for a few dead strings.
 */
export function keepCurrentFormat(keys: string[]): string[] {
  return keys.filter((k) => k.startsWith(PREFIX))
}
