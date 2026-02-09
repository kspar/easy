const config = {
  appName: 'Lahendus',
  emsRoot: import.meta.env.VITE_EMS_ROOT ?? '/v2',
  keycloak: {
    url: import.meta.env.VITE_KEYCLOAK_URL ?? 'https://idp.lahendus.ut.ee/auth/',
    realm: import.meta.env.VITE_KEYCLOAK_REALM ?? 'master',
    clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'lahendus.ut.ee',
  },
  keycloakTokenMinValidSec: 30,
  repoUrl: 'https://github.com/kspar/easy',
} as const

export default config
