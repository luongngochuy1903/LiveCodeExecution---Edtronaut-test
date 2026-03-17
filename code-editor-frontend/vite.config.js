import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      // Mọi request /code-sessions và /executions được forward sang backend
      // → không cần cấu hình CORS trên backend khi dev
      '/code-sessions': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/executions': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
