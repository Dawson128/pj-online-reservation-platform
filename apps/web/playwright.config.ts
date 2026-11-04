import { defineConfig } from '@playwright/test';
import { existsSync } from 'node:fs';

const candidates = [process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'];
const executablePath = candidates.find(path => path && existsSync(path));
export default defineConfig({
  testDir: './tests', fullyParallel: false, workers: 1, retries: 0, timeout: 45000,
  expect: { timeout: 12000 },
  reporter: [['list'], ['json', { outputFile: 'test-results/summary.json' }]],
  use: {
    baseURL: 'https://127.0.0.1:5174', ignoreHTTPSErrors: true,
    viewport: { width: 1280, height: 920 },
    trace: 'off', video: 'off', screenshot: 'off',
    launchOptions: executablePath ? { executablePath } : {},
  },
  webServer: {
    command: 'npm run preview -- --port 5174', url: 'https://127.0.0.1:5174',
    ignoreHTTPSErrors: true, reuseExistingServer: false, timeout: 60000,
    env: {
      APPROVAL_PROXY_TARGET: 'http://127.0.0.1:18092',
      DATABASE_URL: '', DATABASE_USERNAME: '', DATABASE_PASSWORD: '', MIGRATION_DATABASE_URL: '', MIGRATION_USERNAME: '', MIGRATION_PASSWORD: '',
      DB_USERNAME: '', DB_PASSWORD: '', DB_MIGRATION_USERNAME: '', DB_MIGRATION_PASSWORD: '',
      G5_BOOTSTRAP_PASSWORD: '', G5_SERVICE_G1_TOKEN: '', G5_SERVICE_G2_TOKEN: '', G5_SERVICE_G3_TOKEN: '', G5_SERVICE_G4_TOKEN: '', G5_SERVICE_G6_TOKEN: '',
      G5_IDEMPOTENCY_HMAC_KEY: '', G4_SERVICE_G5_TOKEN: '', G6_SERVICE_G5_TOKEN: '',
    },
  },
});
