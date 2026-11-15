import type { ReactNode } from 'react';

export interface Column<T> {
  key: string;
  label: string;
  value: (row: T) => string | number | null;
  render?: (row: T) => ReactNode;
  align?: 'left' | 'right';
  mono?: boolean;
  width?: string;
}

export type SortDir = 'asc' | 'desc';

/** A sorted copy: stable, empty values last in both directions, text in natural order ("CNC-2" before "CNC-10"). */
export function sortRows<T>(rows: T[], column: Column<T>, dir: SortDir): T[] {
  const sign = dir === 'asc' ? 1 : -1;
  return rows
    .map((row, i) => ({ row, i, v: column.value(row) }))
    .sort((a, b) => {
      if (a.v === null || b.v === null) return a.v === b.v ? a.i - b.i : a.v === null ? 1 : -1;
      const c =
        typeof a.v === 'number' && typeof b.v === 'number'
          ? a.v - b.v
          : String(a.v).localeCompare(String(b.v), 'de', { numeric: true });
      return c !== 0 ? sign * c : a.i - b.i;
    })
    .map((x) => x.row);
}
