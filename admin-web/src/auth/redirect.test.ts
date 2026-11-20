import { describe, expect, it } from 'vitest';
import { HOME, safeRedirect } from './redirect';

const origin = 'http://localhost:3000';

describe('safeRedirect', () => {
  it('keeps a path of this app with its search and hash', () => {
    expect(safeRedirect('/', origin)).toBe('/');
    expect(safeRedirect('/drivers?online=true&q=an', origin)).toBe('/drivers?online=true&q=an');
    expect(safeRedirect('/trips/42#track', origin)).toBe('/trips/42#track');
  });

  it.each([
    ['a protocol-relative URL', '//evil.example'],
    ['an absolute URL', 'https://evil.example/drivers'],
    ['a javascript: URL', 'javascript:alert(1)'],
    ['a backslash the URL parser reads as a slash', '/\\evil.example'],
    ['a tab the URL parser drops', '/\t/evil.example'],
    ['a relative path', 'drivers'],
    ['an empty string', ''],
    ['a value that is not a string', 42],
    ['no value', undefined],
    ['the login page itself', '/login?redirect=%2F'],
  ])('falls back to / for %s', (_name, target) => {
    expect(safeRedirect(target, origin)).toBe(HOME);
  });
});
