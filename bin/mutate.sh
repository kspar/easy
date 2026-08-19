#!/usr/bin/env bash
#
# Break the code on purpose, and check the right test notices.
#
#   bin/mutate.sh              # every mutation
#   bin/mutate.sh sweep        # only mutations whose id contains "sweep"
#   bin/mutate.sh --list       # what would run, without running it
#
# ## Why this exists
#
# A passing test proves nothing until it has failed for the reason it names. Over EZ-1766 this
# technique found, among others: a `copyTest` assertion comparing two objects that had *both* been
# through `copyTest`; a duplicate-asset test that constructed the one arrangement any implementation
# repairs; a mid-run guard whose branch never executed; and a `publicUrl` assertion whose fixture
# could not produce the double slash it claimed to catch. Every one of them was green, and every one
# was found by breaking the code and watching nothing happen.
#
# Coverage answers a different question — whether a line ran — and `core/build.gradle.kts` measures
# exactly how differently: removing a whole test class drops the sweep from 94% to 7% and fails,
# while removing two of its tests leaves it at 92% and passes. This is the tool for that gap.
#
# ## Two ways this lies, both fixed here
#
# Both were hit for real, and both report in the *reassuring* direction — a mutation that did not
# happen looks exactly like a suite that caught nothing.
#
#   1. **The edit silently misses.** A regex one backslash short left aae's parser untouched and
#      "59 passed" was read as a result, twice. So every mutation asserts it changed the file, and
#      a mutation that does not apply is a hard error rather than a quiet pass.
#   2. **The build serves a stale artefact.** Gradle held `:tsl:test` up to date and regenerated
#      nothing; Python's `__pycache__` served a mutated module after the restore, because the edit
#      was the same byte length and landed in the same second. So JVM runs use --rerun-tasks and
#      Python runs disable bytecode caching.
#
# Restores by file copy, never `git checkout`: that discards uncommitted work in tracked files and
# does nothing at all for untracked ones.
set -uo pipefail

cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 25 2>/dev/null || true)}"
export JAVA_HOME
BACKUP_DIR="$(mktemp -d)"
trap 'restore_all; rm -rf "$BACKUP_DIR"' EXIT INT TERM

FILTER="${1:-}"
LIST_ONLY=false
[ "$FILTER" = "--list" ] && { LIST_ONLY=true; FILTER=""; }

pass=0; fail=0; skipped=0
FAILED_IDS=()

backup() { mkdir -p "$BACKUP_DIR/$(dirname "$1")"; cp "$1" "$BACKUP_DIR/$1"; }
restore_all() {
  [ -d "$BACKUP_DIR" ] || return 0
  (cd "$BACKUP_DIR" && find . -type f) 2>/dev/null | sed 's|^\./||' | while read -r f; do
    cp "$BACKUP_DIR/$f" "$REPO_ROOT/$f"
  done
}

# mutate <id> <file> <perl-expr> <expected-substring-after> <runner> <must-fail-pattern>
#
# `must-fail-pattern` is a grep -E pattern that has to appear in the run's failure output. Naming
# the test rather than merely requiring "something failed" is what stops a mutation being credited
# to an unrelated flake — and what makes this readable as documentation of which test guards what.
mutate() {
  local id="$1" file="$2" expr="$3" expect="$4" runner="$5" must_fail="$6"

  if [ -n "$FILTER" ] && [[ "$id" != *"$FILTER"* ]]; then return 0; fi
  if $LIST_ONLY; then printf '  %-34s %s\n' "$id" "$file"; return 0; fi
  if [ ! -f "$file" ]; then
    printf '  \033[33mSKIP\033[0m  %-34s (no such file: %s)\n' "$id" "$file"
    skipped=$((skipped + 1)); return 0
  fi

  backup "$file"
  perl -0pi -e "$expr" "$file"

  # 1. Did the edit land? A mutation that silently missed is the failure mode this guard exists for.
  if ! grep -qF -- "$expect" "$file"; then
    printf '  \033[33mSKIP\033[0m  %-34s (mutation did not apply — the pattern no longer matches)\n' "$id"
    cp "$BACKUP_DIR/$file" "$file"
    skipped=$((skipped + 1)); return 0
  fi

  # 2. Run, and require the named test to be among the failures.
  local out
  out="$($runner 2>&1)"
  cp "$BACKUP_DIR/$file" "$file"

  if grep -qE -- "$must_fail" <<<"$out"; then
    printf '  \033[32mCAUGHT\033[0m %s\n' "$id"
    pass=$((pass + 1))
  else
    printf '  \033[31mSURVIVED\033[0m %s\n' "$id"
    printf '         nothing matching /%s/ failed — the guard for this is missing or cannot fail\n' "$must_fail"
    fail=$((fail + 1)); FAILED_IDS+=("$id")
  fi
}

run_core()       { ./gradlew :core:test --rerun-tasks 2>&1; }
run_core_only()  { ./gradlew :core:test --tests "$1" --rerun-tasks 2>&1; }
run_tsl()        { ./gradlew :tsl:test :tsl-common:test --rerun-tasks 2>&1; }
run_aae()        { (cd aae && PYTHONDONTWRITEBYTECODE=1 find . -name __pycache__ -not -path './.venv/*' -exec rm -rf {} + 2>/dev/null; \
                    cd "$REPO_ROOT/aae" && PYTHONDONTWRITEBYTECODE=1 .venv/bin/python -m pytest tests -q --no-header -p no:warnings --tb=no -rf 2>&1); }
# Only the contract spec, not the whole unit suite: it is the only one that reads src/api/types.ts,
# and at ~400 ms a mutation costs less than deciding whether to bother.
run_web_unit()   { (cd "$REPO_ROOT/web" && npx vitest run tests/unit/api-types-contract.test.mjs 2>&1); }

echo "Mutating. Each line is one deliberate defect and whether the suite noticed."
echo

# --- core: the rules that decide a grade or an access ---------------------------------------------

mutate "grading/threshold-boundary" \
  core/src/main/kotlin/core/ems/service/exercise/StudentReadExercises.kt \
  's/    grade >= threshold -> StudentExerciseStatus\.COMPLETED/    grade > threshold -> StudentExerciseStatus.COMPLETED/' \
  'grade > threshold -> StudentExerciseStatus.COMPLETED' \
  'run_core' \
  'GradingBehaviourTest.*(threshold|completes)'

mutate "sweep/scanned-columns" \
  core/src/main/kotlin/core/ems/cron/stored_file_sweep.kt \
  's/    TeacherActivity\.feedbackHtml, TeacherActivity\.feedbackMd,/    TeacherActivity.feedbackMd,/' \
  'TeacherActivity.feedbackMd,' \
  'run_core' \
  'StoredFileSweepTest.*teacher feedback|RichTextColumnsTest'

mutate "sweep/grace-cutoff-inclusive" \
  core/src/main/kotlin/core/ems/cron/stored_file_sweep.kt \
  's/StoredFile\.createdAt less cutoff/StoredFile.createdAt less cutoff.plusHours(1)/' \
  'cutoff.plusHours(1)' \
  'run_core' \
  'StoredFileSweepTest.*strict cutoff'

# The permissive-and-silent one: `student_visible_from = null` means "not scheduled", and reading it
# as "no restriction" would show every unscheduled exercise to every student while looking correct on
# any course whose exercises are all published.
mutate "access/unscheduled-reads-as-visible" \
  core/src/main/kotlin/core/ems/service/access_control/course.kt \
  's/            val isHidden = visibleFrom == null \|\| visibleFrom\.isAfterNow/            val isHidden = visibleFrom != null \&\& visibleFrom.isAfterNow/' \
  'val isHidden = visibleFrom != null' \
  'run_core' \
  'AccessControlRulesTest.*hidden from a student'

# `userOnCourse`'s admin branch is a bypass of the *access* check, not of the existence check.
mutate "access/admin-skips-course-exists" \
  core/src/main/kotlin/core/ems/service/access_control/course.kt \
  's/fun AccessChecksBuilder\.userOnCourse\(courseId: Long\) = add \{ caller: EasyUser ->\n    when \{\n        caller\.isAdmin\(\) -> assertCourseExists\(courseId\)/fun AccessChecksBuilder.userOnCourse(courseId: Long) = add { caller: EasyUser ->\n    when {\n        caller.isAdmin() -> Unit/' \
  'caller.isAdmin() -> Unit' \
  'run_core' \
  'AccessControlRulesTest.*admin to any course that exists'

mutate "security/resource-permitall-too-broad" \
  core/src/main/kotlin/core/conf/security/SecurityConf.kt \
  's{"/\*/resource/\*/\*",}{"/*/resource/**",}' \
  '"/*/resource/**",' \
  'run_core' \
  'FileApiTest.*deeper path'

mutate "articles/cache-key-drops-isadmin" \
  core/src/main/kotlin/core/ems/service/cache/CachingService.kt \
  's/    \@Cacheable\(articleCache\)\n    fun selectLatestArticleVersion/    \@Cacheable(articleCache, key = "#articleIdOrAlias")\n    fun selectLatestArticleVersion/' \
  'key = "#articleIdOrAlias"' \
  'run_core' \
  'ArticleApiTest.*admin reading first'

mutate "autograde/no-retry" \
  core/src/main/kotlin/core/ems/service/submissions.kt \
  's/            log\.error \{ "Autoassessment failed, retrying once more\.\.\. /            throw e; log.error { "Autoassessment failed, retrying once more... /' \
  'throw e; log.error' \
  'run_core' \
  'AutoGradeIntegrationTest.*retried once'

mutate "recompile/writes-to-superseded-row" \
  core/src/main/kotlin/core/ems/service/exercise/AdminRecompileTsl.kt \
  's/        if \(stillCurrent != target\.autoExerciseId\) \{/        if (false) {/' \
  'if (false) {' \
  'run_core' \
  'AdminRecompileTslTest.*version has moved on'

mutate "recompile/ignores-duplicate-rows" \
  core/src/main/kotlin/core/ems/service/exercise/AdminRecompileTsl.kt \
  's/val touched = \(differing \+ stale \+ duplicated\)/val touched = (differing + stale)/' \
  'val touched = (differing + stale)' \
  'run_core' \
  'AdminRecompileTslTest.*duplicate is repaired'

mutate "recompile/sweeps-a-referenced-script" \
  core/src/main/kotlin/core/ems/service/exercise/AdminRecompileTsl.kt \
  's/val \(referenced, stale\) = obsolete\.partition \{ it in target\.gradingScript \}/val (referenced, stale) = emptyList<String>() to obsolete/' \
  'emptyList<String>() to obsolete' \
  'run_core' \
  'AdminRecompileTslTest.*still names'

mutate "storage/public-url-double-slash" \
  core/src/main/kotlin/core/ems/service/storage/S3StorageService.kt \
  "s/publicBaseUrl\.trimEnd\('\/'\)/publicBaseUrl/" \
  'publicBaseUrl}/$key' \
  'run_core' \
  'StorageServiceContractTest.*one slash'

# --- tsl: the compiler whose output nothing used to read ------------------------------------------

mutate "tsl/quoted-dict-keys" \
  tsl/src/main/kotlin/com/example/demo/python_classes.kt \
  's/"(check_type|expected_value|nothing_else|data_category|ignore_case|before_message|passed_message|failed_message|elements_ordered|output_category|file_name)" to/"'"'"'$1'"'"'" to/g' \
  "\"'check_type'\" to" \
  'run_tsl' \
  'GoldenOutputTest|check dictionary keys are bare'

mutate "tsl/literal-not-closeable" \
  tsl/src/main/kotlin/com/example/demo/python_ast.kt \
  's/closeableInTripleQuotes\(value\.replace/(value.replace/' \
  '(value.replace' \
  'run_tsl' \
  'PythonSyntaxTest|GoldenOutputTest'

mutate "tsl/copytest-clears-a-field" \
  tsl-common/src/main/kotlin/tsl/common/model/contains.kt \
  's/    override fun copyTest\(newId: Long\) = copy\(id = newId\)/    override fun copyTest(newId: Long) = copy(id = newId, containsWhatArg = null)/' \
  'containsWhatArg = null' \
  'run_tsl' \
  'copyTest changes the id'

mutate "tsl/property-default-inverted" \
  tsl-common/src/main/kotlin/tsl/common/model/common.kt \
  's/    val mustHaveProperty: Boolean = true,/    val mustHaveProperty: Boolean = false,/' \
  'mustHaveProperty: Boolean = false' \
  'run_tsl' \
  'absent mustHaveProperty'

# --- aae: the executor -----------------------------------------------------------------------------

mutate "aae/no-lahendus-py" \
  aae/containers.py \
  "s/        with open\(os\.path\.join\(student_dir, 'student-submission', 'lahendus\.py'\), mode='w',\n                  encoding='utf-8'\) as submission_file:\n            submission_file\.write\(submission\)\n//" \
  "'submission.py'" \
  'run_aae' \
  'test_the_submission_is_written_under_both_names'

mutate "aae/evaluate-not-executable" \
  aae/containers.py \
  "s/'evaluate\.sh'\), 0o500\)/'evaluate.sh'), 0o400)/" \
  '0o400)' \
  'run_aae' \
  'executable'

mutate "aae/memory-message-says-time" \
  aae/server.py \
  's/        assessment = \(0, MEM_EXCEEDED_MESSAGE\)/        assessment = (0, TIME_EXCEEDED_MESSAGE)/' \
  'assessment = (0, TIME_EXCEEDED_MESSAGE)' \
  'run_aae' \
  'memory_and_not_as_time'

mutate "aae/grade-from-first-line" \
  aae/server.py \
  's/\[-1\]\.lower\(\)\.strip\(\)/[0].lower().strip()/' \
  '[0].lower().strip()' \
  'run_aae' \
  'students_own_output_cannot_be_read'

mutate "aae/validation-disabled" \
  aae/server.py \
  's/    if set\(content\.keys\(\)\) != \{/    if False and set(content.keys()) != {/' \
  'if False and set(content.keys())' \
  'run_aae' \
  'test_every_field_is_required'

# --- web: the client's own picture of what core sends ---------------------------------------------
#
# The first web mutations in this file, and they earn their place: the contract check they exercise
# opened with **one** real finding, and a checker that reports almost nothing is precisely the shape
# that turned out, seven times over, to be a checker that could not report anything.
#
# Each mutation renames or merges rather than simply deleting a line, because the guard above tests
# for the mutated text with `grep -F` and a deletion leaves a substring of the original behind — so
# "did the edit land" would answer yes either way.

mutate "contract/nullable-dropped" \
  web/src/api/types.ts \
  's/  alias: string \| null\n  course_code/  alias: string; course_code/' \
  'alias: string; course_code' \
  'run_web_unit' \
  'nullable-not-declared'

mutate "contract/request-field-renamed" \
  web/src/api/types.ts \
  's/export interface LibraryExerciseUpdate \{\n  title: string/export interface LibraryExerciseUpdate {\n  titel: string/' \
  'titel: string' \
  'run_web_unit' \
  'required-request-field-missing'

mutate "contract/enum-value-dropped" \
  web/src/api/types.ts \
  "s/'UNSTARTED' \\| 'UNGRADED' \\| 'STARTED'/'UNSTARTED' | 'STARTED'/" \
  "= 'UNSTARTED' | 'STARTED' | 'COMPLETED'" \
  'run_web_unit' \
  'enum-value-unhandled'

# The one that guards the guard: a mistyped path must fail, not quietly leave the interface
# unchecked. An annotation nobody can resolve is the same state as no annotation at all, and that
# state is what this whole check exists to end.
mutate "contract/annotation-path-typo" \
  web/src/api/types.ts \
  's/-> courses\[\]/-> kursused[]/' \
  '-> kursused[]' \
  'run_web_unit' \
  'annotation-unresolvable'

if $LIST_ONLY; then exit 0; fi

echo
echo "caught: $pass    survived: $fail    skipped: $skipped"
if [ "$fail" -gt 0 ]; then
  echo
  echo "Survived — for each of these, a defect was introduced and no test noticed:"
  printf '  %s\n' "${FAILED_IDS[@]}"
  echo
  echo "That is a missing guard or one that cannot fail. It is not a reason to delete the mutation."
fi
[ "$fail" -eq 0 ] && [ "$skipped" -eq 0 ]
