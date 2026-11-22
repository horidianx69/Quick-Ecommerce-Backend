// One fetch wrapper for every backend call: adds the login token, sends JSON,
// and turns the backend's error JSON ({ status, title, detail, errors }) into an ApiError.
const BASE = import.meta.env.VITE_API_URL ?? ''
const TOKEN_KEY = 'quike.token'

export function getToken() {
  try { return localStorage.getItem(TOKEN_KEY) } catch { return null }
}
export function setToken(token) {
  try { token ? localStorage.setItem(TOKEN_KEY, token) : localStorage.removeItem(TOKEN_KEY) } catch { /* storage blocked */ }
}

export class ApiError extends Error {
  constructor(status, body) {
    const fields = body?.errors ? Object.entries(body.errors).map(([k, v]) => `${k} ${v}`).join(' · ') : ''
    super(fields || body?.detail || body?.title || `Request failed (${status})`)
    this.status = status
    this.body = body
    this.errors = body?.errors
  }
}

export async function api(path, { method = 'GET', body, query } = {}) {
  let url = BASE + path
  if (query) {
    const qs = new URLSearchParams()
    for (const [k, v] of Object.entries(query)) if (v !== undefined && v !== null && v !== '') qs.set(k, v)
    if ([...qs].length) url += '?' + qs
  }
  const token = getToken()
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  let res
  try {
    res = await fetch(url, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined })
  } catch {
    throw new ApiError(0, { detail: 'Cannot reach the server. Is the backend running?' })
  }

  // Token expired (12 h) or account suspended: drop it so the app shows the login screen.
  if (res.status === 401 && token) {
    setToken(null)
    window.dispatchEvent(new Event('quike:logout'))
  }

  const text = await res.text()
  let data = null
  if (text) { try { data = JSON.parse(text) } catch { data = { detail: text } } }
  if (!res.ok) throw new ApiError(res.status, data)
  return data
}

// For "GET my thing" endpoints where 404 just means "not created yet".
export async function apiOrNull(path, opts) {
  try { return await api(path, opts) } catch (e) { if (e.status === 404) return null; throw e }
}
