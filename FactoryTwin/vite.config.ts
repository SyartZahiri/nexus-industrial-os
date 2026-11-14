import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { fileURLToPath } from 'node:url';

// The Java core compiled by TeaVM (./mvnw -pl platform/web -am package, or npm run core).
const core = fileURLToPath(new URL('../platform/web/target/javascript/nexus-core.js', import.meta.url));

// Relative base so the build works on GitHub Pages under any repository name.
export default defineConfig({
  base: './',
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@nexus-core': core } },
  worker: { format: 'es' },
  build: {
    target: 'es2022',
    chunkSizeWarningLimit: 1600,
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
  test: { environment: 'node' },
});
