import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api/user': { target: 'http://localhost:8081', rewrite: (p) => p.replace('/api', '') },
      '/api/ticket': { target: 'http://localhost:8092', rewrite: (p) => p.replace('/api', '') },
      '/api/order': { target: 'http://localhost:8083', rewrite: (p) => p.replace('/api', '') },
    }
  }
})
