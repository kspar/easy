import type { LibraryExerciseAsset, SolutionFileType } from '../../api/types.ts'
import { TSL_CONTAINER } from './autoEvalTypes.ts'

export const TITLE_MAX_LENGTH = 100

/** The one asset a TSL exercise actually authors; everything else is compiler output. */
export const TSL_SPEC_FILENAME = 'tsl.json'

/** The part of the edited exercise that the auto-assessment tab owns. */
export interface AutoAssessDraft {
  solutionFileName: string
  solutionFileType: SolutionFileType
  /** null = teacher-graded, no auto-assessment at all. */
  containerImage: string | null
  maxTimeSec: number | null
  maxMemMb: number | null
  gradingScript: string
  assets: LibraryExerciseAsset[]
}

export function assetsToMap(assets: LibraryExerciseAsset[]): Record<string, string> {
  return Object.fromEntries(assets.map((a) => [a.file_name, a.file_content]))
}

export function mapToAssets(map: Record<string, string>): LibraryExerciseAsset[] {
  return Object.entries(map).map(([file_name, file_content]) => ({ file_name, file_content }))
}

export function isExerciseTextValid(title: string): boolean {
  return title.trim().length > 0 && title.length <= TITLE_MAX_LENGTH
}

export function isAutoAssessValid(draft: AutoAssessDraft): boolean {
  if (draft.solutionFileName.trim().length === 0) return false
  if (draft.containerImage === null) return true
  return draft.maxTimeSec !== null && draft.maxMemMb !== null
}

/**
 * Assets as they should be sent to `PUT /exercises/{id}`.
 *
 * For a TSL exercise the server recompiles the spec and appends `generated_*.py` and `meta.txt`
 * itself (see `UpdateExercise.kt`), so sending the copies we happen to be holding would duplicate
 * them. Only the spec goes up.
 */
export function assetsForSave(draft: AutoAssessDraft): LibraryExerciseAsset[] | null {
  if (draft.containerImage === null) return null
  if (draft.containerImage === TSL_CONTAINER) {
    return draft.assets.filter((a) => a.file_name === TSL_SPEC_FILENAME)
  }
  return draft.assets
}

/**
 * Three-way merge of one field: if only one side moved, take that side; if both moved to the
 * same value there is no conflict either. Only genuinely divergent edits are reported.
 *
 * Exported for the unit tests. It decides, silently and per field, what a save actually writes
 * when two people edited the same exercise — worth pinning down by example rather than reasoning
 * about at the point of a merge conflict prompt.
 */
export function mergeField<T>(local: T, remote: T, initial: T): [T, boolean] {
  const eq = (a: T, b: T) => JSON.stringify(a) === JSON.stringify(b)
  if (eq(local, initial)) return [remote, false]
  if (eq(remote, initial)) return [local, false]
  if (eq(local, remote)) return [local, false]
  return [local, true]
}
