import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/user': 'http://localhost:8081',
      '/ticket': 'http://localhost:8082',
      '/order': 'http://localhost:8083',
    }
  }
})
