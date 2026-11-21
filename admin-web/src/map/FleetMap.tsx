import { Alert, Box, Text } from '@mantine/core';
import {
  type GeoJSONSource,
  type LngLatBounds,
  Map as MapLibreMap,
  NavigationControl,
  type PointLike,
} from 'maplibre-gl';
import { useEffect, useEffectEvent, useRef, useState } from 'react';
import { type FeatureCollection, syncGeoJson } from '../fleet/geojson';
import type { Bbox } from '../fleet/messages';
import type { FleetStore } from '../fleet/store';
import {
  BASE_STYLE_URL,
  CLUSTERS_SOURCE,
  DRIVERS_SOURCE,
  FALLBACK_STYLE,
  fleetLayers,
  HEADING_IMAGE,
  headingArrow,
  LayerId,
  selectedFilter,
} from './layers';
import { canDrawMap } from './maplibre';
import { bboxOf, type MapView, roundView, sameView } from './view';

// The worker setup and the WebGL check moved to ./maplibre in 12e, to share them with the trip map.
export { canDrawMap };

/** The map stopped moving: where it looks now, and the area to watch. */
export interface MapMove {
  view: MapView;
  bbox: Bbox;
}

export interface FleetMapProps {
  store: FleetStore;
  /** The view in the URL. The map jumps there when it changes from outside, such as Back or a pasted link. */
  view: MapView;
  selectedDriverId: string | undefined;
  /** Called at once with the first view, then each time the map has stood still for 300 ms. */
  onMove: (move: MapMove) => void;
  onSelectDriver: (driverId: string) => void;
}

const MOVE_DEBOUNCE_MS = 300;
/** A click this close to a dot counts. The dots are 3 to 9 px across. */
const CLICK_TOLERANCE_PX = 6;
/** A cluster click zooms in this many levels, where the cells are smaller and fewer drivers fall in the view. */
const CLUSTER_ZOOM_STEP = 2;

function edgesOf(bounds: LngLatBounds) {
  return { west: bounds.getWest(), south: bounds.getSouth(), east: bounds.getEast(), north: bounds.getNorth() };
}

function setSource(map: MapLibreMap, id: string, data: FeatureCollection<object>): void {
  const source = map.getSource<GeoJSONSource>(id);
  // A newer setData replaces one still waiting for the worker. Either way the map shows the newest data.
  source?.setData(data).catch((error: unknown) => {
    console.warn(`Map: could not update ${id}`, error);
  });
}

function addFleetLayers(map: MapLibreMap, selectedDriverId: string | undefined): void {
  const empty: GeoJSON.FeatureCollection = { type: 'FeatureCollection', features: [] };
  const arrow = headingArrow();
  if (arrow !== undefined && !map.hasImage(HEADING_IMAGE))
    map.addImage(HEADING_IMAGE, arrow, { sdf: true, pixelRatio: 2 });
  map.addSource(DRIVERS_SOURCE, { type: 'geojson', data: empty });
  map.addSource(CLUSTERS_SOURCE, { type: 'geojson', data: empty });
  for (const layer of fleetLayers(selectedDriverId)) map.addLayer(layer);
}

/**
 * The live fleet on MapLibre. Markers are never React components: the store becomes GeoJSON at most once a frame, and
 * the GPU draws it. React renders this component only when its props change, never on a socket message.
 */
export function FleetMap({ store, view, selectedDriverId, onMove, onSelectDriver }: FleetMapProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  /** The last view this map reported. Its echo through the URL must not move the map back. */
  const reportedRef = useRef<MapView | null>(null);
  const [webGl] = useState(canDrawMap);
  const [startFailed, setStartFailed] = useState(false);
  const [baseMapFailed, setBaseMapFailed] = useState(false);

  const startView = useEffectEvent(() => view);
  const currentSelection = useEffectEvent(() => selectedDriverId);
  const moved = useEffectEvent((move: MapMove) => {
    reportedRef.current = move.view;
    onMove(move);
  });
  const selectDriver = useEffectEvent((driverId: string) => {
    onSelectDriver(driverId);
  });
  const failedToStart = useEffectEvent((error: unknown) => {
    console.warn('Map: could not start', error);
    setStartFailed(true);
  });

  useEffect(() => {
    const container = containerRef.current;
    if (container === null || !webGl) return undefined;
    const start = startView();
    let map: MapLibreMap;
    try {
      map = new MapLibreMap({
        container,
        style: BASE_STYLE_URL,
        center: [start.lng, start.lat],
        zoom: start.zoom,
        // One world, flat and north up: the server's bbox may not cross the antimeridian, and a rotated view would
        // make the bbox larger than what the operator sees.
        renderWorldCopies: false,
        dragRotate: false,
        pitchWithRotate: false,
        touchPitch: false,
        maxPitch: 0,
      });
    } catch (error) {
      // GPUInitializationError after canDrawMap passed, for example a context lost at once. Only the constructor's
      // throw tells, so the state changes here, once.
      // eslint-disable-next-line react-hooks/set-state-in-effect -- the map library is the external system
      failedToStart(error);
      return undefined;
    }
    mapRef.current = map;
    map.addControl(new NavigationControl({ showCompass: false }), 'top-right');
    map.touchZoomRotate.disableRotation();
    map.keyboard.disableRotation();

    // Layers and the store's feed. `style.load` fires for the base style and again for the fallback.
    let stopSync: (() => void) | undefined;
    let styleLoaded = false;
    map.on('style.load', () => {
      styleLoaded = true;
      addFleetLayers(map, currentSelection());
      stopSync?.();
      stopSync = syncGeoJson(store, {
        setDrivers: (data) => {
          setSource(map, DRIVERS_SOURCE, data);
        },
        setClusters: (data) => {
          setSource(map, CLUSTERS_SOURCE, data);
        },
      });
    });
    // An error before any style loaded means the base style itself failed: draw the drivers on a plain background.
    // Later errors are tiles or glyphs, which cost a part of the base map, not the drivers.
    map.on('error', (event) => {
      console.warn('Map:', event.error.message);
      if (styleLoaded) return;
      styleLoaded = true;
      setBaseMapFailed(true);
      map.setStyle(FALLBACK_STYLE);
    });

    // The subscription follows the map once it stands still. The first one goes out at once, before the base map
    // loads, so the drivers arrive while the tiles do.
    let moveTimer: ReturnType<typeof setTimeout> | undefined;
    const report = () => {
      const center = map.getCenter();
      moved({
        view: roundView({ lat: center.lat, lng: center.lng, zoom: map.getZoom() }),
        bbox: bboxOf(edgesOf(map.getBounds())),
      });
    };
    map.on('movestart', () => {
      clearTimeout(moveTimer);
    });
    map.on('moveend', () => {
      clearTimeout(moveTimer);
      moveTimer = setTimeout(report, MOVE_DEBOUNCE_MS);
    });
    report();

    const near = ({ x, y }: { x: number; y: number }): [PointLike, PointLike] => [
      [x - CLICK_TOLERANCE_PX, y - CLICK_TOLERANCE_PX],
      [x + CLICK_TOLERANCE_PX, y + CLICK_TOLERANCE_PX],
    ];
    const hits = (point: { x: number; y: number }, layer: string) =>
      map.getLayer(layer) === undefined ? [] : map.queryRenderedFeatures(near(point), { layers: [layer] });

    map.on('click', (event) => {
      const cluster = hits(event.point, LayerId.clusters)[0];
      if (cluster?.geometry.type === 'Point') {
        const [lng = 0, lat = 0] = cluster.geometry.coordinates;
        map.easeTo({ center: [lng, lat], zoom: Math.min(map.getZoom() + CLUSTER_ZOOM_STEP, map.getMaxZoom()) });
        return;
      }
      const driverId: unknown = hits(event.point, LayerId.drivers)[0]?.properties.driverId;
      if (typeof driverId === 'string') selectDriver(driverId);
    });
    map.on('mousemove', (event) => {
      const pointer = hits(event.point, LayerId.drivers).length > 0 || hits(event.point, LayerId.clusters).length > 0;
      map.getCanvas().style.cursor = pointer ? 'pointer' : '';
    });

    return () => {
      clearTimeout(moveTimer);
      stopSync?.();
      map.remove();
      mapRef.current = null;
    };
  }, [store, webGl]);

  useEffect(() => {
    const map = mapRef.current;
    if (map?.getLayer(LayerId.selected) === undefined) return;
    map.setFilter(LayerId.selected, selectedFilter(selectedDriverId));
  }, [selectedDriverId]);

  useEffect(() => {
    const map = mapRef.current;
    if (map === null) return;
    const target: MapView = { lat: view.lat, lng: view.lng, zoom: view.zoom };
    // The URL caught up with a move this map reported. The map may have moved on since, so leave it.
    if (reportedRef.current !== null && sameView(target, reportedRef.current)) return;
    const center = map.getCenter();
    if (sameView(target, { lat: center.lat, lng: center.lng, zoom: map.getZoom() })) return;
    map.jumpTo({ center: [target.lng, target.lat], zoom: target.zoom });
  }, [view.lat, view.lng, view.zoom]);

  if (!webGl || startFailed) {
    return (
      <Alert color="yellow" title="The map cannot be drawn">
        <Text size="sm">
          The map needs WebGL 2, and this browser could not start it. Turn on hardware acceleration in the browser
          settings, or use a current browser. The status line and the driver panel still work.
        </Text>
      </Alert>
    );
  }
  return (
    <Box pos="relative" h="100%">
      <Box ref={containerRef} h="100%" />
      {baseMapFailed && (
        <Alert color="yellow" pos="absolute" top={8} left={8} maw={360} p="xs" title="The base map did not load">
          <Text size="xs">The drivers show on a plain background. Check that tiles.openfreemap.org answers.</Text>
        </Alert>
      )}
    </Box>
  );
}
