// Dev server with keycloak-js replaced by a stub, so pages can be driven in a
// browser with no IdP and (with Playwright route interception) no backend.
// See doc/web/browser-testing.md.
//
//   npx vite --config vite.stub.config.ts --port 5199 --strictPort
import { mergeConfig } from 'vite'
import base from './vite.config.ts'
import { fileURLToPath } from 'node:url'

export default mergeConfig(base, {
  resolve: {
    alias: {
      'keycloak-js': fileURLToPath(
        new URL('./dev-harness/keycloak-stub.js', import.meta.url),
      ),
    },
  },
})
