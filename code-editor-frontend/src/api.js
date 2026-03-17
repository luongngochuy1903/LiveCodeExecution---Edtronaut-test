// Tất cả API calls dùng fetch native
// Vite proxy forward request sang localhost:8080 → không cần CORS header

async function http(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({ message: res.statusText }))
    throw new Error(err.message || res.statusText)
  }
  return res.json()
}

export const api = {
  createSession:  (language, templateCode) =>
    http('POST', '/code-sessions', { language, templateCode }),

  autosave: (sessionId, language, sourceCode) =>
    http('PATCH', `/code-sessions/${sessionId}`, { language, sourceCode }),

  run: (sessionId) =>
    http('POST', `/code-sessions/${sessionId}/run`),

  getExecution: (executionId) =>
    http('GET', `/executions/${executionId}`),
}
