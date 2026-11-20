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
  build: {
    rolldownOptions: {
      output: {
        // MapLibre in a chunk of its own, loaded by the map page only. An app change then leaves the library's file
        // name, and the browser's cached copy, as they were.
        codeSplitting: { groups: [{ name: 'maplibre', test: /node_modules[\\/]maplibre-gl[\\/]/ }] },
      },
    },
    // MapLibre alone is about 1 050 kB minified (290 kB gzipped). Every other chunk is far below 500 kB, and the entry
    // is about 300 kB, so the warning still fires if MapLibre ever lands in the entry.
    chunkSizeWarningLimit: 1_100,
  },
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
      // The gate covers the code with the most logic per line: errors, retries, the session and re-minting, and the
      // fleet socket's client, schemas and marker store (12d).
      thresholds: {
        'src/api/**': { lines: 80 },
        'src/auth/**': { lines: 80 },
        'src/fleet/**': { lines: 80 },
      },
    },
  },
});
