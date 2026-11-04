import { defineConfig } from 'vitest/config'

// Playwright owns tests/*.spec.ts; the main workspace's unit tests stay under src.
export default defineConfig({ test: { include: ['src/**/*.test.ts'] } })
