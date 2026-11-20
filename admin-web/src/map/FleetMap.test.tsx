import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { createFleetStore } from '../fleet/store';
import { canDrawMap, FleetMap } from './FleetMap';
import { DEFAULT_VIEW } from './view';

// MapLibre draws with WebGL 2, which jsdom lacks, so the map itself runs only in the E2E test. Here: the check, and
// the message a browser without WebGL 2 gets instead of a crash.

describe('canDrawMap', () => {
  it('is false when the browser makes no WebGL 2 context', () => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
    expect(canDrawMap()).toBe(false);
  });

  it('is true for a WebGL 2 context, and releases it at once', () => {
    class FakeWebGl2 {
      loseContext = vi.fn();
      getExtension(name: string) {
        return name === 'WEBGL_lose_context' ? { loseContext: this.loseContext } : null;
      }
    }
    vi.stubGlobal('WebGL2RenderingContext', FakeWebGl2);
    const context = new FakeWebGl2();
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(context as unknown as RenderingContext);
    try {
      expect(canDrawMap()).toBe(true);
      expect(context.loseContext).toHaveBeenCalledOnce();
    } finally {
      vi.unstubAllGlobals();
    }
  });
});

describe('FleetMap', () => {
  it('shows a message instead of the map when the browser cannot draw it', () => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
    const onMove = vi.fn();
    render(
      <MantineProvider>
        <FleetMap
          store={createFleetStore()}
          view={DEFAULT_VIEW}
          selectedDriverId={undefined}
          onMove={onMove}
          onSelectDriver={vi.fn()}
        />
      </MantineProvider>,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('The map cannot be drawn');
    expect(screen.getByRole('alert')).toHaveTextContent('The status line and the driver panel still work.');
    expect(onMove).not.toHaveBeenCalled();
  });
});
