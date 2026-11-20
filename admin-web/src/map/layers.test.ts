import { validateStyleMin } from '@maplibre/maplibre-gl-style-spec';
import { describe, expect, it } from 'vitest';
import { CLUSTERS_SOURCE, DRIVERS_SOURCE, FALLBACK_STYLE, fleetLayers, LayerId, selectedFilter } from './layers';

// MapLibre checks a layer only when the map adds it, in a browser. The style spec's own validator runs the same checks
// here, so a wrong expression (a zoom expression that is not the outermost one, say) fails a unit test, not the map.

function styleWith(selected: string | undefined) {
  const empty = { type: 'FeatureCollection' as const, features: [] };
  return {
    ...FALLBACK_STYLE,
    sources: {
      [DRIVERS_SOURCE]: { type: 'geojson' as const, data: empty },
      [CLUSTERS_SOURCE]: { type: 'geojson' as const, data: empty },
    },
    layers: [...FALLBACK_STYLE.layers, ...fleetLayers(selected)],
  };
}

describe('fleet layers', () => {
  it('pass the MapLibre style validator, with and without a selected driver', () => {
    expect(validateStyleMin(styleWith(undefined))).toEqual([]);
    expect(validateStyleMin(styleWith('7c9e6679-7425-40de-944b-e07fc1f90ae7'))).toEqual([]);
  });

  it('draw clusters under the drivers, and the selection ring under the dots', () => {
    expect(fleetLayers(undefined).map((layer) => layer.id)).toEqual([
      LayerId.clusters,
      LayerId.clusterCounts,
      LayerId.selected,
      LayerId.drivers,
      LayerId.headings,
    ]);
  });

  it('match no driver when none is selected', () => {
    expect(selectedFilter(undefined)).toEqual(['==', ['get', 'driverId'], '']);
  });
});
