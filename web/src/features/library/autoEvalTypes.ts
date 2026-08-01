/**
 * The auto-assessment container types offered in the library exercise editor, ported from
 * wui's `AutoEvalTypes`. Each entry is a template: picking a type seeds the eval script,
 * assets and resource limits.
 *
 * `editor: 'TSL'` means the spec is authored by the visual TSL builder rather than by editing
 * script files directly.
 */
export interface AutoEvalType {
  name: string
  container: string
  allowedTime: number
  allowedMemory: number
  editor: 'TSL' | 'CODE'
  /** Shown under the type selector; plain text, not markup. */
  helpText?: string
  evaluateScript: string
  assets: Record<string, string>
}

export const TSL_CONTAINER = 'tiivad:tsl-compose'

export const AUTO_EVAL_TYPES: AutoEvalType[] = [
  {
    name: 'TSL',
    container: TSL_CONTAINER,
    allowedTime: 7,
    allowedMemory: 30,
    editor: 'TSL',
    evaluateScript: 'cd student-submission\npython generated_0.py',
    assets: { 'generated_0.py': '' },
  },
  {
    name: 'Silmused PostgreSQL',
    container: 'silmused',
    allowedTime: 30,
    allowedMemory: 50,
    editor: 'CODE',
    evaluateScript: [
      'cd student-submission',
      'mv lahendus.py lahendus.sql',
      '',
      'service postgresql start >/dev/null 2>&1',
      'silmused lahendus.sql tests.py postgres localhost 5433 postgres',
    ].join('\n'),
    assets: {
      'tests.py': [
        'from silmused.TitleLayer import TitleLayer',
        'from silmused.ChecksLayer import ChecksLayer',
        'from silmused.ExecuteLayer import ExecuteLayer',
        'from silmused.tests.DataTest import DataTest',
        'from silmused.tests.StructureTest import StructureTest',
        'from silmused.tests.ConstraintTest import ConstraintTest',
        'from silmused.tests.FunctionTest import FunctionTest',
        'from silmused.tests.IndexTest import IndexTest',
        'from silmused.tests.ProcedureTest import ProcedureTest',
        'from silmused.tests.TriggerTest import TriggerTest',
        'from silmused.tests.ViewTest import ViewTest',
        '',
        'tests = [',
        '    ',
        ']',
      ].join('\n'),
    },
  },
  {
    name: 'Python Grader',
    container: 'pygrader',
    allowedTime: 7,
    allowedMemory: 30,
    editor: 'CODE',
    helpText:
      'Uute automaatkontrollide koostamist Python Graderiga ei soovita, aga teegi leiab siit: https://github.com/kspar/python-grader',
    evaluateScript: 'cd student-submission\npython -m grader.easy',
    assets: {
      'tester.py': [
        'from grader import *',
        'from grader.utils import *',
        '',
        '@test',
        '@expose_ast',
        '@set_description("Test 1")',
        'def test1(m, AST):',
        '    pass',
      ].join('\n'),
    },
  },
  {
    name: 'Pildituvastus',
    container: 'imgrec',
    allowedTime: 20,
    allowedMemory: 50,
    editor: 'CODE',
    evaluateScript: [
      'cd student-submission',
      'python3 kontroll.py',
      'xvfb-run python3 modified_student_submission.py',
      '',
      'python3 -m grader.easy --assets screenshot.jpg tester_2.py --no-solution-file',
    ].join('\n'),
    assets: {},
  },
]

export function autoEvalTypeOf(containerImage: string | null | undefined): AutoEvalType | undefined {
  return AUTO_EVAL_TYPES.find((t) => t.container === containerImage)
}

/** Whether this exercise's assets are authored through the visual TSL builder. */
export function isTslContainer(containerImage: string | null | undefined): boolean {
  return containerImage === TSL_CONTAINER
}
