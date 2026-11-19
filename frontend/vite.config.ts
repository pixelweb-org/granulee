import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The backend address; override with GRANULEE_API when it does not run on localhost:8080.
const api = process.env.GRANULEE_API ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': api },
  },
  preview: {
    proxy: { '/api': api },
  },
});
