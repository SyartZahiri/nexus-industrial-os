import { describe, expect, it } from 'vitest';
import { groupedAreas } from './groups';

describe('groupedAreas', () => {
  it('groups the areas in a fixed order and keeps every area once', () => {
    const groups = groupedAreas();
    expect(groups.map((g) => g.group)).toEqual(['Betrieb', 'Systeme', 'Auswertung', 'System']);
    expect(groups.flatMap((g) => g.areas.map((a) => a.id))).toHaveLength(11);
    expect(groups[0].areas.map((a) => a.id)).toEqual(['overview', 'twin', 'machines', 'warehouse']);
  });
});
