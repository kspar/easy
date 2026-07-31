import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
    rules: {
      // --- React Compiler rules, and why they are tuned rather than left at their defaults ---
      //
      // eslint-plugin-react-hooks v6+ enables the React Compiler's analysis rules in its
      // recommended preset. We do NOT run the compiler: there is no babel-plugin-react-compiler
      // dependency and vite.config.ts uses a bare react(). See EZ-1721 for enabling it.
      //
      // Most of these rules are still worth keeping, because they flag genuine Rules of React
      // violations whether or not the compiler runs — that is how the Date.now()-during-render
      // and the mutated-useState-value bugs were found and fixed. Two exceptions:

      // Only meaningful once the compiler is enabled. Its message is literally "React Compiler
      // has skipped optimizing this component" — advice with no consequence while nothing
      // compiles. Turn this back on in the same change that enables the compiler (EZ-1721), at
      // which point the fix is to delete the manual useMemo, not to repair its deps.
      'react-hooks/preserve-manual-memoization': 'off',

      // Real problems, but a pre-existing backlog of 38 across 18 files that cannot be cleared
      // safely in bulk: the set-state-in-effect ones are dialogs syncing props into state, whose
      // fix (remount via `key`) lands in the parent call sites, and the refs ones are in the
      // CodeMirror-heavy grading screens where refs are load-bearing. Warnings so `npm run lint`
      // can gate CI on errors today instead of waiting for all 38. Tracked in EZ-1722.
      //
      // Note the trade-off: while these are warnings, a NEW violation of them will not fail CI.
      // Raise them back to 'error' as the backlog is cleared, file by file if need be.
      'react-hooks/refs': 'warn',
      'react-hooks/set-state-in-effect': 'warn',
    },
  },
])
