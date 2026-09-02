import type { TFunction } from 'i18next'
import { ApiResponseError } from './client.ts'

/**
 * One sentence for a failed request, from the error code core already sends.
 *
 * Core answers every failure with `{id, code, attrs, log_msg}` and `client.ts` has always parsed it
 * into `ApiResponseError.errorBody`. Before this, **two** call sites in the whole application read
 * it (audit X-035); nineteen files rendered `general.somethingWentWrong` instead, so a taken course
 * name, a malformed deadline, a group with students still in it and a server that was simply down
 * were the same sentence. The mechanism was there; nothing used it.
 *
 * Deliberately a lookup and not nineteen bespoke handlers. A call site that wants to say something
 * better about one specific code still can — `ShareDialog` does — and everything else improves by
 * changing `catch` to call this.
 *
 * `log_msg` is never shown. It is written for whoever reads the server log and frequently contains
 * ids, table names and Kotlin type names.
 */

/** Codes worth a sentence, from `core/exception/error_response.kt`. */
const CODE_KEYS: Record<string, string> = {
  // Access — the user is asking for something that is not theirs.
  ROLE_NOT_ALLOWED: 'errors.roleNotAllowed',
  NO_COURSE_ACCESS: 'errors.noCourseAccess',
  NO_GROUP_ACCESS: 'errors.noGroupAccess',
  NO_EXERCISE_ACCESS: 'errors.noExerciseAccess',
  NO_DIR_ACCESS: 'errors.noDirAccess',
  CANNOT_MODIFY_OWN: 'errors.cannotModifyOwn',

  // The thing is not there, or is already there.
  ENTITY_WITH_ID_NOT_FOUND: 'errors.notFound',
  ENTITY_WITH_ID_ALREADY_EXISTS: 'errors.alreadyExists',
  ACCOUNT_EMAIL_NOT_FOUND: 'errors.accountEmailNotFound',
  ARTICLE_NOT_FOUND: 'errors.articleNotFound',
  ARTICLE_ALIAS_IN_USE: 'errors.articleAliasInUse',

  // Refused because of something the user can go and change.
  GROUP_NOT_EMPTY: 'errors.groupNotEmpty',
  DIR_NOT_EMPTY: 'errors.dirNotEmpty',
  EXERCISE_USED_ON_COURSE: 'errors.exerciseUsedOnCourse',
  ARTICLE_PUBLISHED: 'errors.articlePublished',
  COURSE_EXERCISE_CLOSED: 'errors.courseExerciseClosed',
  STUDENT_NOT_ON_COURSE: 'errors.studentNotOnCourse',
  EXERCISE_NOT_AUTOASSESSABLE: 'errors.exerciseNotAutoassessable',
  EXERCISE_WRONG_SOLUTION_TYPE: 'errors.exerciseWrongSolutionType',
  INVALID_PARAMETER_VALUE: 'errors.invalidParameterValue',

  // Grading and Moodle — transient, and the advice differs from "something went wrong".
  ASSESSMENT_AWAIT_TIMEOUT: 'errors.assessmentAwaitTimeout',
  MOODLE_SYNC_IN_PROGRESS: 'errors.moodleSyncInProgress',
  // The fallback only: with `attrs.course_title` present, `errorMessage` names the course instead.
  MOODLE_COURSE_ALREADY_LINKED: 'errors.moodleCourseAlreadyLinked',
  MOODLE_LINKING_ERROR: 'errors.moodleLinkingError',
  MOODLE_EMPTY_RESPONSE: 'errors.moodleEmptyResponse',
  MOODLE_GRADE_SYNC_ERROR: 'errors.moodleGradeSyncError',
  ACCOUNT_MIGRATION_FAILED: 'errors.accountMigrationFailed',
  BUG_REPORT_RATE_LIMITED: 'errors.bugReportRateLimited',

  // TSL_COMPILE_FAILED is deliberately absent: X-018 gives it a fuller treatment, with the
  // compiler's own diagnostic behind a disclosure. A one-liner here would be a downgrade.
}

/**
 * `ENTITY_WITH_ID_NOT_FOUND` is core's general-purpose miss, so the code alone cannot say *what*
 * was missing — but `attrs` can, and nothing has ever read it. Sharing a directory with an unknown
 * address is the case that matters: `PutDirAccess` throws it with `email`, and until now the dialog
 * looked for a code named `ACCOUNT_NOT_FOUND` that core does not have, so the branch was dead and
 * the user got a raw internal message.
 */
const NOT_FOUND_BY_ATTR: Record<string, string> = {
  email: 'errors.emailNotFound',
}

/** Codes whose `attrs.email` names the address, so the message can too. */
const EMAIL_CODES = new Set(['ENTITY_WITH_ID_NOT_FOUND', 'ACCOUNT_EMAIL_NOT_FOUND'])

/**
 * Adding teachers is a bulk paste, so `AddTeachersToCourse` reports *every* address it could not
 * resolve in `attrs.emails`, comma-separated — not just the first one it tripped over (EZ-1830).
 * Naming them is the whole point of the message: a teacher who pasted thirty lines needs to know
 * which of them to go and fix.
 */
function unresolvedEmails(attrs: Record<string, string> | undefined): string[] {
  return (attrs?.emails ?? '')
    .split(',')
    .map((e) => e.trim())
    .filter(Boolean)
}

/**
 * The codes that mean "you may not", as opposed to "it broke".
 *
 * The distinction is not cosmetic. A refusal is the system working, and the person reading it needs
 * a *person* — whoever runs the course — not the bug tracker. Offering the reporter here is how
 * EZ-1858 happened: a student who was not enrolled read an accurate sentence, was handed a "Report
 * it" button by the same alert, and filed an engineering ticket about their own enrolment.
 *
 * `CANNOT_MODIFY_OWN` is deliberately absent. It refuses an action rather than an area, the person
 * hitting it is already a teacher or admin, and there is nobody to be sent to about it.
 */
const ACCESS_CODES = new Set([
  'ROLE_NOT_ALLOWED',
  'NO_COURSE_ACCESS',
  'NO_GROUP_ACCESS',
  'NO_EXERCISE_ACCESS',
  'NO_DIR_ACCESS',
])

/** True when core refused on permission grounds — see [ACCESS_CODES]. */
export function isAccessError(err: unknown): boolean {
  // Nullable as well as optional: core sends `code: null` on a failure it has no code for.
  const code = err instanceof ApiResponseError ? err.errorBody?.code : null
  return code != null && ACCESS_CODES.has(code)
}

export function errorMessage(err: unknown, t: TFunction): string {
  const generic = t('general.somethingWentWrong')

  // A server that is genuinely down does not produce an ApiResponseError at all: `fetch` rejects
  // with a TypeError before there is a status to read. Checking `status >= 500` alone therefore
  // caught only nginx's own error page, and missed the outage it was written for.
  if (!(err instanceof ApiResponseError)) {
    return err instanceof TypeError ? t('errors.serverUnreachable') : generic
  }

  const body = err.errorBody
  if (!body?.code) {
    // A response with no envelope: a gateway or proxy answered instead of core.
    return err.status >= 500 ? t('errors.serverUnreachable') : generic
  }

  if (body.code === 'ACCOUNT_EMAIL_NOT_FOUND') {
    const emails = unresolvedEmails(body.attrs)
    // One address reads better in a sentence; several read better as a list after a colon.
    if (emails.length === 1) return t('errors.emailNotFound', { value: emails[0] })
    if (emails.length > 1) return t('errors.emailsNotFound', { value: emails.join(', ') })
  }

  // The admin's next step is to unlink the *other* course, so the sentence has to say which one
  // (EZ-1877). Core sends its title; the id is there too but a title is what the course list shows.
  if (body.code === 'MOODLE_COURSE_ALREADY_LINKED' && body.attrs?.course_title) {
    return t('errors.moodleCourseAlreadyLinkedTo', { value: body.attrs.course_title })
  }

  if (EMAIL_CODES.has(body.code)) {
    for (const [attr, key] of Object.entries(NOT_FOUND_BY_ATTR)) {
      const value = body.attrs?.[attr]
      if (value) return t(key, { value })
    }
  }

  const key = CODE_KEYS[body.code]
  return key ? t(key) : generic
}
