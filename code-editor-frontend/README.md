# Code Editor Frontend

React + Vite. Không dùng thư viện ngoài nào ngoài React.

## Chạy

```bash
npm install
npm run dev
```

Mở http://localhost:3000 — backend phải chạy tại http://localhost:8080.

## CORS

Vite proxy forward request sang backend nên không cần cấu hình CORS.
Cấu hình trong `vite.config.js`:
- `/code-sessions/**` → `http://localhost:8080`
- `/executions/**`    → `http://localhost:8080`

## Cấu trúc

```
src/
├── api.js       fetch wrapper gọi 4 endpoint
├── App.jsx      toàn bộ UI + logic
├── index.css    dark theme
└── main.jsx     entry point
```
