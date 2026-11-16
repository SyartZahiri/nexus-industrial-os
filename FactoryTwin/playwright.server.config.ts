import { defineConfig } from '@playwright/test';
import { base } from './playwright.config';

/** Server runtime: Spring Boot with PostgreSQL and OPC UA on 8080, the interface in server mode on 5179. */
export default defineConfig({
  ...base,
  testIgnore: ['fps.spec.ts'], // the frame rate is a property of the interface, measured once in browser mode
  use: { ...base.use, baseURL: 'http://localhost:5179' },
  webServer: [
    {
      command: 'node scripts/run-server.mjs',
      url: 'http://localhost:8080/api/v1/sim',
      reuseExistingServer: !process.env.CI, // in CI Playwright always starts its own servers
      timeout: 300_000,
    },
    {
      command: 'npm run dev -- --mode server --port 5179 --strictPort',
      url: 'http://localhost:5179',
      reuseExistingServer: !process.env.CI, // in CI Playwright always starts its own servers
      timeout: 120_000,
    },
  ],
});
