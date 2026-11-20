import { describe, expect, it } from 'vitest';
import { nextMove, previousMove, readTrail } from './paging';

describe('the cursor trail (C1)', () => {
  it('adds the current cursor on "Next", with null for the first page', () => {
    expect(nextMove(undefined, [], 'c-2')).toEqual({ cursor: 'c-2', trail: [null] });
    expect(nextMove('c-2', [null], 'c-3')).toEqual({ cursor: 'c-3', trail: [null, 'c-2'] });
  });

  it('goes back to the last cursor on "Previous"', () => {
    expect(previousMove([null, 'c-2'])).toEqual({ cursor: 'c-2', trail: [null] });
    expect(previousMove([null])).toEqual({ cursor: undefined, trail: [] });
  });

  it('reads anything but a list of cursors as no trail', () => {
    expect(readTrail(undefined)).toEqual([]);
    expect(readTrail('c-2')).toEqual([]);
    expect(readTrail([1, 2])).toEqual([]);
    expect(readTrail([null, 'c-2'])).toEqual([null, 'c-2']);
  });
});
