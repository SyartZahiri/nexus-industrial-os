import { describe, expect, it } from 'vitest';
import { AREAS } from './areas';
import { hrefOf, parseHash } from './route';

describe('route', () => {
  it('has the eleven areas of the spec, in navigation order', () => {
    expect(AREAS.map((a) => a.label)).toEqual([
      'Overview',
      'FactoryTwin',
      'SmartFactory',
      'FlowPilot',
      'RouteMind',
      'Machines',
      'Warehouse',
      'Analytics',
      'Event Monitor',
      'Scenario Lab',
      'Settings',
    ]);
  });

  it('reads an area and an optional parameter', () => {
    expect(parseHash('#/twin')).toEqual({ area: 'twin', param: null });
    expect(parseHash('#/machines/CNC-01')).toEqual({ area: 'machines', param: 'CNC-01' });
    expect(parseHash('#/events/PO-0004')).toEqual({ area: 'events', param: 'PO-0004' });
  });

  it('unknownHashesLandOnTheOverview', () => {
    for (const h of ['', '#', '#/', '#/maschinen', '#twin', 'twin']) {
      expect(parseHash(h).area, h).toBe('overview');
    }
  });

  it('writes links that read back to the same place', () => {
    for (const a of AREAS) expect(parseHash(hrefOf(a.id)).area).toBe(a.id);
    expect(parseHash(hrefOf('machines', 'ROB-01'))).toEqual({ area: 'machines', param: 'ROB-01' });
    expect(hrefOf('events', 'PO 1/2')).toBe('#/events/PO%201%2F2');
    expect(parseHash('#/events/PO%201%2F2').param).toBe('PO 1/2');
  });

  it('aTrailingSlashKeepsTheArea', () => {
    expect(parseHash('#/events/')).toEqual({ area: 'events', param: null });
  });
});
