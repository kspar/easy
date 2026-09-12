/**
 * Unit coverage for the two pure parts of `src/components/downloadTextFile.ts` (EZ-1903).
 *
 * Both exist because of something the browser does badly. `filenameFromContentDisposition` reads
 * the name core sends for an exported submission — core sends it unquoted, most of the world quotes
 * it, and getting it wrong means a teacher's file is called `submission` with no extension.
 * `fileTimestamp` replaced `Date.now()`, and the reason it is not `toLocaleString('sv')` — which is
 * the neat one-liner the old interface used — is the colons, which Windows will not have in a
 * filename.
 *
 * The saving itself is not covered here: it is three DOM calls and a timer, and asserting on them
 * would be asserting that the lines are in the order they are written in.
 *
 *   npm run test:unit          # from web/
 */
import { expect, test } from 'vitest'
import { fileTimestamp, filenameFromContentDisposition } from '../../src/components/downloadTextFile.ts'

test('download-file', () => {
  // --- the name core actually sends ---------------------------------------------------------
  // TeacherDownloadCourseExerciseSubmissions writes `attachment; filename=<name>`, no quotes.
  expect(filenameFromContentDisposition('attachment; filename=4147_Maasikas_Mari_77_lahendus.py'))
    .toBe('4147_Maasikas_Mari_77_lahendus.py')

  // Quoted is the commoner form everywhere else, and costs nothing to accept.
  expect(filenameFromContentDisposition('attachment; filename="submissions_119_4147.zip"'))
    .toBe('submissions_119_4147.zip')

  // Spacing around the `=` and a capitalised parameter are both legal.
  expect(filenameFromContentDisposition('attachment; FileName = report.csv')).toBe('report.csv')

  // A parameter after the filename must not be swallowed into it.
  expect(filenameFromContentDisposition('attachment; filename="a.py"; size=42')).toBe('a.py')

  // --- nothing usable, so the caller's fallback should win ------------------------------------
  expect(filenameFromContentDisposition(null)).toBe(null)
  expect(filenameFromContentDisposition('attachment')).toBe(null)
  expect(filenameFromContentDisposition('attachment; filename=')).toBe(null)
  expect(filenameFromContentDisposition('attachment; filename="  "')).toBe(null)
  // RFC 5987 only. Core does not send this, and half-reading it would produce a mangled name.
  expect(filenameFromContentDisposition("attachment; filename*=UTF-8''t%C3%B6%C3%B6.py")).toBe(null)

  // --- a filename is not a path ----------------------------------------------------------------
  expect(filenameFromContentDisposition('attachment; filename="../../etc/passwd"'))
    .toBe('.._.._etc_passwd')
  expect(filenameFromContentDisposition('attachment; filename=".."')).toBe(null)

  // --- the timestamp ---------------------------------------------------------------------------
  // Local time, not UTC: the person reading the filename is in their own timezone, and the old
  // interface's `toLocaleString` was local too.
  const stamp = fileTimestamp(new Date(2026, 8, 12, 14, 3, 7))
  expect(stamp).toBe('2026-09-12_14-03-07')

  // The point of the whole function: nothing Windows refuses in a filename.
  expect(stamp).not.toMatch(/[:\\/*?"<>|]/)

  // Still sorts chronologically as text, which is what the ISO-ish shape is for.
  const earlier = fileTimestamp(new Date(2026, 8, 12, 9, 59, 59))
  expect(earlier < stamp).toBe(true)
  expect(fileTimestamp(new Date(2025, 11, 31, 23, 59, 59)) < earlier).toBe(true)
})
