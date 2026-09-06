/** Past this a file is not somebody's solution, it is somebody's data set. */
const MAX_SOLUTION_BYTES = 300_000

export type SolutionFileResult =
  | { ok: true; text: string }
  | { ok: false; reason: 'tooLarge' | 'notText' }

/**
 * Reads a chosen or dropped file as the text of a solution.
 *
 * Decodes strictly rather than leniently: a `.pdf` dragged in by mistake would otherwise arrive as
 * a screenful of replacement characters and be submitted as one, and "it graded my homework as
 * gibberish" is a worse failure than being told the file is not text.
 */
export async function readSolutionFile(file: File): Promise<SolutionFileResult> {
  if (file.size > MAX_SOLUTION_BYTES) return { ok: false, reason: 'tooLarge' }
  try {
    const buffer = await file.arrayBuffer()
    return { ok: true, text: new TextDecoder('utf-8', { fatal: true }).decode(buffer) }
  } catch {
    return { ok: false, reason: 'notText' }
  }
}

/** The i18n key for a failed read, so both editors say the same thing about the same file. */
export function solutionFileErrorKey(reason: 'tooLarge' | 'notText'): string {
  return reason === 'tooLarge'
    ? 'submission.uploadErrorTooLarge'
    : 'submission.uploadErrorNotText'
}
