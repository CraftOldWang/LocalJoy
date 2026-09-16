import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const proxy = {
  '/api': {
    target: process.env.BACKEND_URL || 'http://127.0.0.1:8081',
    changeOrigin: true,
    rewrite: path => path.replace(/^\/api/, ''),
    timeout: 15000,
    proxyTimeout: 15000
  }
};
export default defineConfig({
  plugins: [react()],
  server: { host: '127.0.0.1', port: Number(process.env.PORT || 8088), strictPort: true, proxy },
  preview: { proxy },
  build: { sourcemap: false }
});
