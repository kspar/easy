import config from '../config.ts'

export interface ApiError {
  id: string
  code: string | null
  attrs: Record<string, string>
  log_msg: string
}

export class ApiResponseError extends Error {
  constructor(
    public status: number,
    public errorBody: ApiError | null,
  ) {
    super(errorBody?.log_msg ?? `HTTP ${status}`)
  }
}

export let getToken: (() => Promise<string | undefined>) | null = null

export function setTokenProvider(provider: () => Promise<string | undefined>) {
  getToken = provider
}

export async function apiFetch<T>(
  path: string,
  options: {
    method?: string
    body?: unknown
    headers?: Record<string, string>
    noAuth?: boolean
  } = {},
): Promise<T> {
  const { method = 'GET', body, headers = {}, noAuth = false } = options

  const combinedHeaders: Record<string, string> = {
    'Content-Type': 'application/json',
    ...headers,
  }

  if (!noAuth && getToken) {
    const token = await getToken()
    if (token) {
      combinedHeaders['Authorization'] = `Bearer ${token}`
    }
  }

  const response = await fetch(`${config.emsRoot}${path}`, {
    method,
    headers: combinedHeaders,
    body: body != null ? JSON.stringify(body) : undefined,
  })

  if (!response.ok) {
    let errorBody: ApiError | null = null
    try {
      errorBody = await response.json()
    } catch {
      // no parseable error body
    }
    throw new ApiResponseError(response.status, errorBody)
  }

  if (response.status === 204) {
    return undefined as T
  }

  const text = await response.text()
  if (!text) {
    return undefined as T
  }
  return JSON.parse(text)
}