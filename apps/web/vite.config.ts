import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  build: { target: 'es2022', sourcemap: true },
  server: { port: 4173, strictPort: true },
  preview: { port: 4174, strictPort: true }
});
