# JPlag Integration (Plagiarism Detection)

Replace the current naive similarity check with [JPlag](https://github.com/jplag/jplag), a proper source code plagiarism detection tool. JPlag uses token-based comparison and is language-aware (understands syntax, not just text similarity).

**Prerequisite:** Java 11 → 25 migration (or at minimum Java 17) — see `doc/java-25-migration.md`.

## Current state

The existing similarity feature (`core/src/main/kotlin/core/ems/service/code/TeacherCheckSimilarity.kt`) uses:
- **FuzzySearch** (fuzzywuzzy library) — generic string similarity
- **Dice coefficient** — bigram-based text overlap

These are text-level comparisons that can be easily fooled by renaming variables, reordering functions, or adding whitespace. They also don't understand language syntax.

The React frontend has a placeholder page at `web/src/features/similarity/SimilarityPage.tsx`.

## What JPlag provides

- **Token-based comparison** — parses source code into language-specific tokens, so renaming variables or reformatting doesn't affect results
- **Language support** — Java, Python, C/C++, JavaScript, Kotlin, and many more
- **Pairwise comparison** — compares all submission pairs efficiently
- **Cluster detection** — groups similar submissions into clusters
- **Built-in report generation** — HTML report with side-by-side diff views

## Integration approach

JPlag can be used as a **Java library** (not just CLI). Add `de.jplag:jplag` as a dependency in `core/build.gradle`.

## Backend changes

1. **Add JPlag dependency** to `core/build.gradle`:
   ```
   implementation 'de.jplag:jplag:<latest>'
   ```

2. **New service** (or rewrite `TeacherCheckSimilarity.kt`):
   - Collect latest submissions for a course exercise
   - Write them to a temp directory (JPlag reads from filesystem)
   - Configure JPlag with the correct language (based on exercise config or file extension)
   - Run comparison, parse results
   - Return structured results (pairs with similarity scores, matched token ranges)

3. **API design** — either keep the existing `POST /v2/exercises/{exerciseId}/similarity` endpoint and change the response format, or create a new endpoint. The response should include:
   - Pairwise similarity scores (percentage)
   - Matched code regions (line ranges in both submissions)
   - Cluster information (groups of similar submissions)

4. **Language detection** — exercises need a language hint. Options:
   - Infer from file extension of submissions
   - Add a `language` field to exercise configuration
   - Let teacher select when running the check

## Frontend changes

Replace the placeholder `SimilarityPage.tsx` with:
- A trigger button/form (select course exercise, optionally filter by courses)
- Results table showing pairwise similarity scores, sorted by similarity
- Click-through to side-by-side diff view showing matched regions
- Cluster visualization (optional, could be a later phase)

## Phasing

- **Phase 1**: Backend JPlag integration — run comparison, return scores via API. Reuse existing endpoint pattern.
- **Phase 2**: Frontend results table — show pairwise scores, link to submissions.
- **Phase 3**: Side-by-side matched code view — show exactly which parts match.
- **Phase 4**: Cluster view, batch operations (e.g. flag all submissions in a cluster).

## Cleanup after migration

- Remove `fuzzywuzzy` dependency (`me.xdrop:fuzzywuzzy:1.2.0` in `core/build.gradle`)
- Remove or rewrite `TeacherCheckSimilarity.kt` (custom Dice coefficient + FuzzySearch logic)
