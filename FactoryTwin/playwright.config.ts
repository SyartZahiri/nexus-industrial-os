import { defineConfig } from '@playwright/test';

/** Shared by both runtimes. One worker: every test resets the one plant it talks to. */
export const base = defineConfig({
  testDir: 'e2e',
  timeout: 8 * 60_000,
  expect: { timeout: 15_000 },
  workers: 1,
  reporter: [['list']],
  use: {
    channel: process.env.CI ? undefined : 'chrome', // locally the installed Chrome, no browser download
    viewport: { width: 1440, height: 900 },
  },
});

/** Browser runtime: the Java core in the page's web worker (Spec 14, GitHub Pages mode). */
export default defineConfig({
  ...base,
  use: { ...base.use, baseURL: 'http://localhost:5173' },
  webServer: {
    command: 'npm run dev -- --port 5173 --strictPort',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI, // in CI Playwright always starts its own servers
    timeout: 120_000,
  },
});
