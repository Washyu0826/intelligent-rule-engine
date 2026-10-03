import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'

const backendTarget = process.env.VITE_BACKEND_TARGET ?? 'http://localhost:8088'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/tools': {
        target: backendTarget,
        changeOrigin: true,
        // SSE streaming 需要禁用緩衝
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            if (proxyRes.headers['content-type']?.includes('text/event-stream')) {
              proxyRes.headers['cache-control'] = 'no-cache';
              proxyRes.headers['x-accel-buffering'] = 'no';
            }
          });
        },
      },
      '/actuator': {
        target: backendTarget,
        changeOrigin: true,
      },
      '/health': {
        target: backendTarget,
        changeOrigin: true,
      },
      '/auth': {
        target: backendTarget,
        changeOrigin: true,
      },
      '/engine': {
        target: backendTarget,
        changeOrigin: true,
      },
      '/rules': {
        target: backendTarget,
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules/framer-motion/')) {
            return 'framer-motion'
          }

          if (
            id.includes('node_modules/react/') ||
            id.includes('node_modules/react-dom/')
          ) {
            return 'react-vendor'
          }

          return undefined
        },
      },
    },
  },
})
