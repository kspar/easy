/**
 * Named starting points for the "Add test" menu.
 *
 * **Why these exist.** Collapsing 39 test types into 4 (EZ-1607) fixed the model but moved a cost
 * onto discovery. Before it, a teacher wanting to check for a loop opened the type list and picked
 * `program_contains_loop_test` — the type name *was* the intent. Now they must pick "Code
 * contains…", set the scope, set the mode to Keyword, and know that "a loop" means the keywords
 * `for` and `while`. Nothing on screen tells them that last part.
 *
 * A preset is just a factory returning an ordinary test. No new types, nothing to migrate, and the
 * result is fully editable the moment it lands — it only front-loads the configuration a retired
 * type used to imply.
 *
 * **Choosing the list.** These cover what the retired types were for, weighted by how many of them
 * shared a theme (six `*_contains_loop`/`try_except` variants, three `*_imports_module`, two
 * `*_calls_print`, and so on). It is an informed guess, not evidence: the spec migration will
 * produce a real frequency table of what teachers actually reach for, and this list should be
 * revisited against it then.
 *
 * Presets that can be complete are complete — "Uses a loop" arrives with `for` and `while` filled
 * in. Ones that need a name the preset cannot know arrive with the values field empty and already
 * showing its required state, which is the same "fill this in" affordance a new function execution
 * test uses.
 */
import {
  createTest,
  genericCheckField,
  nextId,
  type EditableTestType,
  type TslTest,
} from './tslModel.ts'

type Translate = (key: string, opts?: Record<string, unknown>) => string

export interface TslPreset {
  /** Suffix of the `tsl.preset.*` label key, and the value the menu item carries. */
  id: string
  build: (t: Translate) => TslTest
}

/** A test of `type` with the given fields overlaid, keeping everything `createTest` declares. */
function from(type: EditableTestType, t: Translate, patch: Record<string, unknown> = {}): TslTest {
  return { ...createTest(type, nextId(), t), ...patch }
}

/** As `from`, but for the collapsed static tests, whose single check usually needs adjusting too. */
function fromStatic(
  type: EditableTestType,
  t: Translate,
  patch: Record<string, unknown>,
  check: Record<string, unknown> = {},
): TslTest {
  const base = from(type, t, patch)
  return { ...base, genericCheck: { ...genericCheckField(base), ...check } }
}

export const PRESET_GROUPS: { labelKey: string; presets: TslPreset[] }[] = [
  {
    labelKey: 'tsl.groupExecution',
    presets: [
      { id: 'runProgram', build: (t) => from('program_execution_test', t) },
      { id: 'callFunction', build: (t) => from('function_execution_test', t) },
    ],
  },
  {
    labelKey: 'tsl.presetGroupContent',
    presets: [
      {
        // ANY_OF_THESE, not ALL: either loop keyword satisfies "uses a loop".
        id: 'usesLoop',
        build: (t) =>
          fromStatic('contains_test', t, {}, { checkType: 'ANY_OF_THESE', expectedValue: ['for', 'while'] }),
      },
      {
        // ALL_OF_THESE here: a try without an except is not error handling.
        id: 'usesTryExcept',
        build: (t) =>
          fromStatic('contains_test', t, {}, { checkType: 'ALL_OF_THESE', expectedValue: ['try', 'except'] }),
      },
      {
        id: 'importsModule',
        build: (t) =>
          fromStatic(
            'contains_test',
            t,
            { containsWhat: 'KEYWORD_WITH_PRECEDING_ARG', containsWhatArg: 'import' },
            { checkType: 'ANY_OF_THESE' },
          ),
      },
      {
        id: 'containsText',
        build: (t) => fromStatic('contains_test', t, { containsWhat: 'PHRASE' }, {}),
      },
    ],
  },
  {
    labelKey: 'tsl.presetGroupCalls',
    presets: [
      { id: 'callsFunction', build: (t) => fromStatic('calls_test', t, {}, {}) },
      {
        // The old `*_calls_print_test` was almost always used in the negative — "solve it without
        // printing" — which is now NONE_OF_THESE rather than a `mustNotCall` boolean.
        id: 'doesNotPrint',
        build: (t) =>
          fromStatic('calls_test', t, {}, { checkType: 'NONE_OF_THESE', expectedValue: ['print'] }),
      },
    ],
  },
  {
    labelKey: 'tsl.presetGroupDefines',
    presets: [
      { id: 'definesFunction', build: (t) => fromStatic('definition_test', t, {}, {}) },
      {
        id: 'definesClass',
        build: (t) => fromStatic('definition_test', t, { definitionCheckType: 'CLASS' }, {}),
      },
    ],
  },
  {
    labelKey: 'tsl.presetGroupProperties',
    presets: [
      { id: 'isRecursive', build: (t) => from('function_is_test', t) },
      {
        id: 'isPure',
        build: (t) => from('function_is_test', t, { functionProperty: 'PURE' }),
      },
    ],
  },
  {
    labelKey: 'tsl.groupOther',
    presets: [{ id: 'blank', build: (t) => from('placeholder_test', t) }],
  },
]
