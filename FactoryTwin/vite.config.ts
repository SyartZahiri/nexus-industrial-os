import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// The Java core compiled by TeaVM (./mvnw -pl platform/web -am package, or npm run core).
// Absolute file path from a file URL without node types: /C:/x on Windows becomes C:/x.
const core = decodeURIComponent(new URL('../platform/web/target/javascript/nexus-core.js', import.meta.url).pathname)
  .replace(/^\/([A-Za-z]:)/, '$1');

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
    // the core module lives outside this folder, in platform/web/target
    fs: { allow: ['..'] },
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
  test: { environment: 'node' },
});
