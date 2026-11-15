import { describe, expect, it } from 'vitest';
import { sortRows, type Column } from './table';

interface Row {
  id: string;
  n: number | null;
}
const rows: Row[] = [
  { id: 'CNC-10', n: 3 },
  { id: 'CNC-2', n: null },
  { id: 'CNC-1', n: 3 },
  { id: 'ROB-01', n: 1 },
];
const byId: Column<Row> = { key: 'id', label: 'ID', value: (r) => r.id };
const byN: Column<Row> = { key: 'n', label: 'N', value: (r) => r.n };

describe('sortRows', () => {
  it('sorts text naturally, numbers numerically', () => {
    expect(sortRows(rows, byId, 'asc').map((r) => r.id)).toEqual(['CNC-1', 'CNC-2', 'CNC-10', 'ROB-01']);
    expect(sortRows(rows, byN, 'asc').map((r) => r.id)).toEqual(['ROB-01', 'CNC-10', 'CNC-1', 'CNC-2']);
  });

  it('keeps equal rows in their order and puts empty values last in both directions', () => {
    expect(sortRows(rows, byN, 'desc').map((r) => r.id)).toEqual(['CNC-10', 'CNC-1', 'ROB-01', 'CNC-2']);
  });

  it('does not change the rows it was given', () => {
    const copy = [...rows];
    sortRows(rows, byId, 'desc');
    expect(rows).toEqual(copy);
  });
});
