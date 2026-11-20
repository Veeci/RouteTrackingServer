// @ts-check
import js from '@eslint/js';
import { defineConfig } from 'eslint/config';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default defineConfig(
  { ignores: ['dist', 'coverage', 'generated', 'playwright-report', 'test-results'] },
  js.configs.recommended,
  // Type-checked rules: they read the TypeScript types, so they catch a promise nobody awaits
  // (no-floating-promises), an `any` leaking out of a library, and a condition that is always true.
  tseslint.configs.strictTypeChecked,
  tseslint.configs.stylisticTypeChecked,
  {
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      // Numbers in template strings are fine: `${count} drivers`.
      '@typescript-eslint/restrict-template-expressions': ['error', { allowNumber: true }],
      // TanStack Router redirects by throwing its `redirect()` result, a Response, from beforeLoad.
      '@typescript-eslint/only-throw-error': [
        'error',
        { allow: [{ from: 'package', package: '@tanstack/router-core', name: 'Redirect' }] },
      ],
    },
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    extends: [reactHooks.configs.flat['recommended-latest']],
    languageOptions: { globals: globals.browser },
  },
  {
    files: ['*.{js,ts}', 'e2e/**/*.ts'],
    languageOptions: { globals: globals.node },
  },
  // Plain JavaScript (this file) is in no tsconfig, so it gets the untyped rules only.
  {
    files: ['**/*.js'],
    extends: [tseslint.configs.disableTypeChecked],
  },
);
