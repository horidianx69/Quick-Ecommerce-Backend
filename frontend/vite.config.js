import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// The dev server forwards /api to the Spring Boot backend, so the browser sees one origin.
// The Origin header is dropped so the backend's CORS check (which only allows :3000) doesn't refuse it.
const backend = {
  target: 'http://localhost:8080',
  configure: (proxy) => proxy.on('proxyReq', (req) => req.removeHeader('origin')),
}

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: { '/api': backend, '/v3': backend },
  },
})
