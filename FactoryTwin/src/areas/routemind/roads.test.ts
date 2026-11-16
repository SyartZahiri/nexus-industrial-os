import { describe, expect, it } from 'vitest';
import roadsFile from '../../../public/routemind/roads.json';
import { decodePolyline, legPath, pointAlong, type Roads } from './roads';

const roads = roadsFile as Roads;

describe('roads.json', () => {
  it('names its source and has a road geometry for every pair of places', () => {
    expect(roads.attribution).toContain('OpenStreetMap');
    expect(roads.places[0].id).toBe('D0');
    expect(roads.places).toHaveLength(31);
    for (let i = 0; i < roads.places.length; i++) {
      for (let j = i + 1; j < roads.places.length; j++) {
        const key = `${roads.places[i].id}>${roads.places[j].id}`;
        expect(decodePolyline(roads.geometry[key]).length, key).toBeGreaterThanOrEqual(2);
      }
    }
  });
});

describe('geometry', () => {
  it('decodes Google polylines as [lon, lat]', () => {
    // the example of the format description: (38.5, -120.2), (40.7, -120.95), (43.252, -126.453)
    expect(decodePolyline('_p~iF~ps|U_ulLnnqC_mqNvxq`@')).toEqual([
      [-120.2, 38.5],
      [-120.95, 40.7],
      [-126.453, 43.252],
    ]);
  });

  it('runs a leg in the asked direction', () => {
    const there = legPath(roads, 'D0', 'K24');
    const back = legPath(roads, 'K24', 'D0');
    expect(back).toEqual([...there].reverse());
    expect(legPath(roads, 'D0', 'D0')).toHaveLength(1);
  });

  it('finds the point at a share of the length', () => {
    const path: [number, number][] = [
      [8, 47],
      [8, 47.1],
      [8, 47.3],
    ];
    expect(pointAlong(path, 0)).toEqual([8, 47]);
    expect(pointAlong(path, 1)).toEqual([8, 47.3]);
    const mid = pointAlong(path, 0.5);
    expect(mid[0]).toBe(8);
    expect(mid[1]).toBeCloseTo(47.15, 6);
  });
});
