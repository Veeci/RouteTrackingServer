import 'maplibre-gl/dist/maplibre-gl.css';
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url';
import { setWorkerUrl } from 'maplibre-gl';

// The MapLibre setup every map shares: the fleet map (12d) and the trip map (12e). A map component imports this
// module, so the setup runs once, in the lazy `maplibre` chunk, before the first map starts.

// 12d choice C1: MapLibre 6 starts its worker from this file, which Vite copies next to the app's own scripts. The
// policy then says `worker-src 'self'`, with no blob: worker and no eval.
setWorkerUrl(workerUrl);

/**
 * Whether the browser can make a WebGL 2 context, which MapLibre 6 needs. A browser can know WebGL 2 and still refuse a
 * context, when hardware acceleration is off or the GPU is blocked. The test context is released at once, because a
 * page may hold only a few.
 */
export function canDrawMap(): boolean {
  try {
    const context = document.createElement('canvas').getContext('webgl2');
    context?.getExtension('WEBGL_lose_context')?.loseContext();
    // jsdom has no WebGL at all, so the class is missing there and the check throws, which also means "no".
    return context instanceof WebGL2RenderingContext;
  } catch {
    return false;
  }
}
