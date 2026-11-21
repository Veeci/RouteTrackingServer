import { Alert, Box, Text } from '@mantine/core';
import { type GeoJSONSource, LngLatBounds, Map as MapLibreMap, NavigationControl } from 'maplibre-gl';
import { useEffect, useEffectEvent, useRef, useState } from 'react';
import { BASE_STYLE_URL, FALLBACK_STYLE, HEADING_IMAGE, headingArrow } from './layers';
import { canDrawMap } from './maplibre';
import {
  allPositions,
  type Collection,
  TRIP_LINES_SOURCE,
  TRIP_POINTS_SOURCE,
  tripLayers,
  tripLines,
  type TripMapData,
  tripPoints,
} from './tripLayers';
import { DEFAULT_VIEW } from './view';

export interface TripMapProps {
  data: TripMapData;
}

/** Room around the trip when the camera fits it, so the markers do not touch the edge. */
const FIT_PADDING_PX = 48;
/** The closest the first fit zooms, so a trip of a few metres does not open at street-sign level. */
const FIT_MAX_ZOOM = 16;

function setSource(map: MapLibreMap, id: string, data: Collection<object>): void {
  // A newer setData replaces one still waiting for the worker. Either way the map shows the newest data.
  map
    .getSource<GeoJSONSource>(id)
    ?.setData(data as GeoJSON.FeatureCollection)
    .catch((error: unknown) => {
      console.warn(`Map: could not update ${id}`, error);
    });
}

function addTripLayers(map: MapLibreMap): void {
  const empty: GeoJSON.FeatureCollection = { type: 'FeatureCollection', features: [] };
  const arrow = headingArrow();
  if (arrow !== undefined && !map.hasImage(HEADING_IMAGE)) {
    map.addImage(HEADING_IMAGE, arrow, { sdf: true, pixelRatio: 2 });
  }
  map.addSource(TRIP_LINES_SOURCE, { type: 'geojson', data: empty });
  map.addSource(TRIP_POINTS_SOURCE, { type: 'geojson', data: empty });
  for (const layer of tripLayers()) map.addLayer(layer);
}

/**
 * One trip on MapLibre: the pickup, the destination, the planned route, the recorded legs or the live trail, and the
 * driver. It shares 12d's worker setup, base style and fallback. The camera fits the trip once, when the map starts,
 * and then stays where the operator puts it. New data only replaces the two GeoJSON sources.
 */
export function TripMap({ data }: TripMapProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const styleReadyRef = useRef(false);
  const [webGl] = useState(canDrawMap);
  const [startFailed, setStartFailed] = useState(false);
  const [baseMapFailed, setBaseMapFailed] = useState(false);

  const currentData = useEffectEvent(() => data);
  const failedToStart = useEffectEvent((error: unknown) => {
    console.warn('Map: could not start', error);
    setStartFailed(true);
  });

  useEffect(() => {
    const container = containerRef.current;
    if (container === null || !webGl) return undefined;
    let map: MapLibreMap;
    try {
      map = new MapLibreMap({
        container,
        style: BASE_STYLE_URL,
        center: [DEFAULT_VIEW.lng, DEFAULT_VIEW.lat],
        zoom: DEFAULT_VIEW.zoom,
        renderWorldCopies: false,
        dragRotate: false,
        pitchWithRotate: false,
        touchPitch: false,
        maxPitch: 0,
      });
    } catch (error) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- the map library is the external system
      failedToStart(error);
      return undefined;
    }
    mapRef.current = map;
    map.addControl(new NavigationControl({ showCompass: false }), 'top-right');
    map.touchZoomRotate.disableRotation();
    map.keyboard.disableRotation();

    // The first fit happens before the base map loads, so the tiles of the trip's area load first.
    const bounds = new LngLatBounds();
    for (const position of allPositions(currentData())) bounds.extend(position);
    if (!bounds.isEmpty()) map.fitBounds(bounds, { padding: FIT_PADDING_PX, maxZoom: FIT_MAX_ZOOM, animate: false });

    let styleLoaded = false;
    map.on('style.load', () => {
      styleLoaded = true;
      addTripLayers(map);
      styleReadyRef.current = true;
      const latest = currentData();
      setSource(map, TRIP_LINES_SOURCE, tripLines(latest));
      setSource(map, TRIP_POINTS_SOURCE, tripPoints(latest));
    });
    // An error before any style loaded means the base style itself failed: draw the trip on a plain background.
    map.on('error', (event) => {
      console.warn('Map:', event.error.message);
      if (styleLoaded) return;
      styleLoaded = true;
      styleReadyRef.current = false;
      setBaseMapFailed(true);
      map.setStyle(FALLBACK_STYLE);
    });

    return () => {
      styleReadyRef.current = false;
      map.remove();
      mapRef.current = null;
    };
  }, [webGl]);

  useEffect(() => {
    const map = mapRef.current;
    if (map === null || !styleReadyRef.current) return;
    setSource(map, TRIP_LINES_SOURCE, tripLines(data));
    setSource(map, TRIP_POINTS_SOURCE, tripPoints(data));
  }, [data]);

  if (!webGl || startFailed) {
    return (
      <Alert color="yellow" title="The map cannot be drawn">
        <Text size="sm">
          The map needs WebGL 2, and this browser could not start it. Turn on hardware acceleration in the browser
          settings, or use a current browser. The rest of the page still works.
        </Text>
      </Alert>
    );
  }
  return (
    <Box pos="relative" h="100%">
      <Box ref={containerRef} h="100%" />
      {baseMapFailed && (
        <Alert color="yellow" pos="absolute" top={8} left={8} maw={360} p="xs" title="The base map did not load">
          <Text size="xs">The trip shows on a plain background. Check that tiles.openfreemap.org answers.</Text>
        </Alert>
      )}
    </Box>
  );
}
