import { apiFetch } from './client.ts'

export interface TslCompiledScript {
  name: string
  value: string
}

export interface TslCompileMeta {
  timestamp: string
  compiler_version: string
  backend_id: string
  backend_version: string
}

export interface TslCompileResp {
  scripts: TslCompiledScript[] | null
  /** Non-null when the compiler rejected the spec; the text is meant to be shown verbatim. */
  feedback: string | null
  meta: TslCompileMeta | null
}

export function compileTsl(tslSpec: string, format: 'JSON' | 'YAML' = 'JSON') {
  return apiFetch<TslCompileResp>('/tsl/compile', {
    method: 'POST',
    body: { tsl_spec: tslSpec, format },
  })
}
