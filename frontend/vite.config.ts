import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const backendTarget = 'http://127.0.0.1:8000'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  optimizeDeps: { include: ['pdfjs-dist'] },
  server: {
    host: true,
    proxy: {
      '/api': {
        target: backendTarget,
        changeOrigin: true,
        ws: true,
      },
      '/health': {
        target: backendTarget,
        changeOrigin: true,
      },
      '/voices': {
        target: backendTarget,
        changeOrigin: true,
      },
    },
  },
})
