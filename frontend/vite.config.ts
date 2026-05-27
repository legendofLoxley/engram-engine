import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/cognitive': 'http://localhost:18792',
      '/health': 'http://localhost:18792',
      '/onboard': 'http://localhost:18792',
    },
  },
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
})
