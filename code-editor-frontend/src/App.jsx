import React, { useState, useEffect, useRef, useCallback } from 'react'
import Editor from '@monaco-editor/react'
import { api } from './api'

const AUTOSAVE_INTERVAL = 12_000
const POLL_INTERVAL     = 1_500
const TERMINAL_STATES   = ['COMPLETED', 'FAILED', 'TIMEOUT']
const DEFAULT_CODE      = `# Write your Python code here\nprint("Hello, World!")\n`

const LANG_MAP = {
  PYTHON: 'python',
  JAVASCRIPT: 'javascript',
}

export default function App() {
  const [sessionId,  setSessionId]  = useState(null)
  const [language,   setLanguage]   = useState('PYTHON')
  const [code,       setCode]       = useState(DEFAULT_CODE)
  const [saveState,  setSaveState]  = useState('idle')
  const [execution,  setExecution]  = useState(null)
  const [isRunning,  setIsRunning]  = useState(false)
  const [error,      setError]      = useState(null)

  const lastSavedCode = useRef(DEFAULT_CODE)
  const pollRef       = useRef(null)

  useEffect(() => {
    api.createSession('PYTHON', DEFAULT_CODE)
      .then(data => {
        setSessionId(data.sessionId)
        lastSavedCode.current = DEFAULT_CODE
      })
      .catch(() => setError('Không kết nối được backend tại localhost:8080'))
  }, [])

  useEffect(() => {
    if (!sessionId) return
    const timer = setInterval(() => {
      if (code !== lastSavedCode.current) performSave(code, true)
    }, AUTOSAVE_INTERVAL)
    return () => clearInterval(timer)
  }, [sessionId, code]) // eslint-disable-line

  useEffect(() => () => clearInterval(pollRef.current), [])

  const performSave = useCallback(async (sourceCode, isAuto = false) => {
    if (!sessionId) return
    setSaveState('saving')
    try {
      await api.autosave(sessionId, language, sourceCode)
      lastSavedCode.current = sourceCode
      setSaveState('saved')
      setTimeout(() => setSaveState('idle'), isAuto ? 2000 : 1500)
    } catch {
      setSaveState('error')
      setTimeout(() => setSaveState('idle'), 3000)
    }
  }, [sessionId, language])

  const handleSave = () => performSave(code)

  const handleRun = async () => {
    if (!sessionId || isRunning) return
    await performSave(code)
    setIsRunning(true)
    setExecution({ status: 'QUEUED' })
    clearInterval(pollRef.current)
    try {
      const { executionId } = await api.run(sessionId)
      pollRef.current = setInterval(async () => {
        try {
          const result = await api.getExecution(executionId)
          setExecution(result)
          if (TERMINAL_STATES.includes(result.status)) {
            clearInterval(pollRef.current)
            setIsRunning(false)
          }
        } catch (e) {
          setExecution({ status: 'FAILED', stderr: e.message })
          clearInterval(pollRef.current)
          setIsRunning(false)
        }
      }, POLL_INTERVAL)
    } catch (e) {
      setExecution({ status: 'FAILED', stderr: e.message })
      setIsRunning(false)
    }
  }

  if (error) {
    return (
      <div className="error-screen">
        <p>⚠ {error}</p>
        <button onClick={() => window.location.reload()}>Thử lại</button>
      </div>
    )
  }

  return (
    <div className="layout">
      <header className="header">
        <span className="logo">CodeRunner</span>
        {sessionId
          ? <span className="session-id">session: {sessionId.slice(0, 8)}…</span>
          : <span className="session-id">Đang khởi tạo…</span>
        }
        <div className="header-actions">
          {saveState === 'saving' && <span className="tag tag-amber">Đang lưu…</span>}
          {saveState === 'saved'  && <span className="tag tag-green">Đã lưu ✓</span>}
          {saveState === 'error'  && <span className="tag tag-red">Lưu thất bại</span>}

          <select value={language} onChange={e => setLanguage(e.target.value)} disabled={isRunning}>
            <option value="PYTHON">Python</option>
            <option value="JAVASCRIPT">JavaScript</option>
          </select>

          <button className="btn btn-secondary" onClick={handleSave}
            disabled={!sessionId || saveState === 'saving'}>
            Save
          </button>

          <button className="btn btn-primary" onClick={handleRun}
            disabled={!sessionId || isRunning}>
            {isRunning ? '⟳ Đang chạy…' : '▶ Run'}
          </button>
        </div>
      </header>

      <div className="main">
        <div className="editor-panel">
          {!sessionId ? (
            <div className="editor-loading">Đang kết nối…</div>
          ) : (
            <Editor
              height="100%"
              language={LANG_MAP[language]}
              value={code}
              onChange={val => setCode(val ?? '')}
              theme="vs-dark"
              options={{
                fontSize: 14,
                fontFamily: "'Fira Code', 'Cascadia Code', 'Consolas', monospace",
                fontLigatures: true,
                minimap: { enabled: false },
                scrollBeyondLastLine: false,
                tabSize: 4,
                insertSpaces: true,
                wordWrap: 'on',
                lineNumbers: 'on',
                renderLineHighlight: 'line',
                automaticLayout: true,
                padding: { top: 16, bottom: 16 },
              }}
            />
          )}
        </div>

        <div className="output-panel">
          <div className="output-header">
            <span>Output</span>
            {execution && <StatusBadge status={execution.status} />}
            {execution?.executionTimeMs != null && (
              <span className="exec-time">{execution.executionTimeMs}ms</span>
            )}
          </div>
          <div className="output-body">
            {!execution && <p className="hint">Nhấn ▶ Run để thực thi code</p>}
            {execution && !TERMINAL_STATES.includes(execution.status) && (
              <p className="hint">⟳ {execution.status}…</p>
            )}
            {execution?.stdout && (
              <div>
                <div className="output-label stdout-label">stdout</div>
                <pre className="output-pre">{execution.stdout}</pre>
              </div>
            )}
            {execution?.stderr && (
              <div>
                <div className="output-label stderr-label">stderr</div>
                <pre className="output-pre stderr-pre">{execution.stderr}</pre>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

function StatusBadge({ status }) {
  const cls = {
    QUEUED:    'tag tag-gray',
    RUNNING:   'tag tag-blue',
    COMPLETED: 'tag tag-green',
    FAILED:    'tag tag-red',
    TIMEOUT:   'tag tag-amber',
  }[status] || 'tag tag-gray'
  return <span className={cls}>{status}</span>
}
