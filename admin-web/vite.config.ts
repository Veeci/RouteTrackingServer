import react from '@vitejs/plugin-react';
import type { ProxyOptions } from 'vite';
import { defineConfig } from 'vitest/config';
import { securityHeaders } from './csp.ts';

// The browser talks to one origin, the Vite server, which forwards the API and the sockets to the RouteTracking
// server. CORS never comes up. RTS_API_URL points it at another server, for example the admin port 8081.
const apiTarget = process.env.RTS_API_URL ?? 'http://localhost:8080';

const proxy: Record<string, ProxyOptions> = {
  '/api': { target: apiTarget, changeOrigin: true },
  '/ws': { target: apiTarget, changeOrigin: true, ws: true },
};

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, strictPort: true, proxy },
  // `vite preview` serves the production build with the production headers, so the E2E test runs under the real
  // Content Security Policy. The dev server cannot: React's fast refresh needs an inline script.
  preview: { port: 4173, strictPort: true, proxy, headers: securityHeaders },
  test: {
    environment: 'jsdom',
    setupFiles: ['src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    restoreMocks: true,
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/main.tsx'],
      reporter: ['text', 'html'],
      // The gate covers the code with the most logic per line: errors, retries, the session and re-minting.
      thresholds: {
        'src/api/**': { lines: 80 },
        'src/auth/**': { lines: 80 },
      },
    },
  },
});
