import { Map as MapLibreMap, NavigationControl, Popup, setWorkerUrl, type GeoJSONSource, type StyleSpecification } from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
// MapLibre looks for its worker next to its own module, which a bundler moves; Vite bundles the worker and names it
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';
import { useEffect, useRef, useState } from 'react';
import type { RmDelivery, RmTour } from '../../nexus/types';
import { twin } from '../../store/factoryStore';
import type { Roads } from './roads';
import { placesGeoJson, routesGeoJson, trucksGeoJson } from './routemindView';

const STYLE: StyleSpecification = {
  version: 8,
  sources: {
    osm: {
      type: 'raster',
      tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
      tileSize: 256,
      maxzoom: 19,
      attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>-Mitwirkende',
    },
  },
  // dimmed and desaturated, so the tours stand out on the dark surface
  layers: [{ id: 'osm', type: 'raster', source: 'osm', paint: { 'raster-saturation': -0.7, 'raster-brightness-max': 0.5 } }],
};
const EMPTY = { type: 'FeatureCollection' as const, features: [] };
setWorkerUrl(workerUrl);

/** Depot, customers, tours along the roads and the vehicles; a click on a tour line selects it. */
export default function RouteMap({ roads, tours, deliveries, selected, onSelect }: {
  roads: Roads;
  tours: RmTour[];
  deliveries: RmDelivery[];
  selected: string | null;
  onSelect: (tourId: string | null) => void;
}) {
  const box = useRef<HTMLDivElement>(null);
  const map = useRef<MapLibreMap | null>(null);
  const select = useRef(onSelect);
  select.current = onSelect;
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const d0 = roads.places[0];
    const m = new MapLibreMap({ container: box.current!, style: STYLE, center: [d0.lon + 0.12, d0.lat - 0.02], zoom: 9.3, attributionControl: { compact: false } });
    m.addControl(new NavigationControl({ showCompass: false }), 'top-right');
    m.on('load', () => {
      m.addSource('routes', { type: 'geojson', data: EMPTY });
      m.addLayer({
        id: 'routes',
        type: 'line',
        source: 'routes',
        layout: { 'line-join': 'round', 'line-cap': 'round' },
        paint: { 'line-color': ['get', 'color'], 'line-width': ['case', ['get', 'selected'], 5, 3], 'line-opacity': ['case', ['get', 'dim'], 0.2, 0.9] },
      });
      m.addSource('places', { type: 'geojson', data: EMPTY });
      m.addLayer({
        id: 'places',
        type: 'circle',
        source: 'places',
        paint: {
          'circle-radius': ['case', ['get', 'depot'], 8, ['get', 'open'], 6, 3.5],
          'circle-color': ['case', ['get', 'depot'], '#e5e7eb', ['get', 'color']],
          'circle-stroke-color': '#0b0f14',
          'circle-stroke-width': 1.5,
        },
      });
      m.addSource('trucks', { type: 'geojson', data: EMPTY });
      m.addLayer({
        id: 'trucks',
        type: 'circle',
        source: 'trucks',
        paint: { 'circle-radius': 7, 'circle-color': ['get', 'color'], 'circle-stroke-color': '#f8fafc', 'circle-stroke-width': 2 },
      });
      m.on('click', 'routes', (e) => select.current((e.features?.[0]?.properties?.tourId as string | undefined) ?? null));
      m.on('mouseenter', 'routes', () => (m.getCanvas().style.cursor = 'pointer'));
      m.on('mouseleave', 'routes', () => (m.getCanvas().style.cursor = ''));
      m.on('click', 'places', (e) => {
        const p = e.features?.[0]?.properties;
        if (p) new Popup({ closeButton: false }).setLngLat(e.lngLat).setText(`${p.id} · ${p.name}`).addTo(m);
      });
      setReady(true);
    });
    map.current = m;
    return () => {
      setReady(false); // a new map has no sources until its own load event
      m.remove();
    };
  }, [roads]);

  useEffect(() => {
    if (!ready || !map.current) return;
    (map.current.getSource('routes') as GeoJSONSource).setData(routesGeoJson(roads, tours, selected));
    (map.current.getSource('places') as GeoJSONSource).setData(placesGeoJson(roads, deliveries));
  }, [ready, roads, tours, deliveries, selected]);

  // vehicles follow the frames, four times a second
  useEffect(() => {
    if (!ready) return;
    const id = window.setInterval(() => {
      const trucks = twin.latest?.trucks ?? [];
      (map.current?.getSource('trucks') as GeoJSONSource | undefined)?.setData(trucksGeoJson(roads, trucks));
    }, 250);
    return () => window.clearInterval(id);
  }, [ready, roads]);

  return <div ref={box} role="region" aria-label="Karte der Touren" className="h-[480px] w-full overflow-hidden rounded-md border border-line" />;
}
