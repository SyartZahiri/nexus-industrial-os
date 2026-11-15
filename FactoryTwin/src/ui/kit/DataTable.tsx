import { useMemo, useState, type ReactNode } from 'react';
import { cx } from '../primitives';
import { sortRows, type Column, type SortDir } from './table';

/**
 * The shared table: sortable column heads (aria-sort), rows reachable by keyboard when they lead somewhere, and
 * horizontal scrolling inside the table instead of the page.
 */
export function DataTable<T>({
  rows,
  columns,
  rowKey,
  initialSort,
  onRowClick,
  empty = 'Keine Einträge.',
  label,
}: {
  rows: T[];
  columns: Column<T>[];
  rowKey: (row: T) => string;
  initialSort?: { key: string; dir: SortDir };
  onRowClick?: (row: T) => void;
  empty?: ReactNode;
  label: string;
}) {
  const [sort, setSort] = useState(initialSort ?? null);
  const shown = useMemo(() => {
    const column = sort ? columns.find((c) => c.key === sort.key) : undefined;
    return column && sort ? sortRows(rows, column, sort.dir) : rows;
  }, [rows, columns, sort]);
  const toggle = (key: string) =>
    setSort((s) => (s?.key === key ? { key, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { key, dir: 'asc' }));
  return (
    <div className="scroll-thin overflow-x-auto">
      <table className="w-full border-collapse text-[12px]" aria-label={label}>
        <thead>
          <tr className="border-b border-line">
            {columns.map((c) => (
              <th
                key={c.key}
                scope="col"
                style={{ width: c.width }}
                aria-sort={sort?.key === c.key ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'}
                className={cx('px-3 py-2 font-medium', c.align === 'right' ? 'text-right' : 'text-left')}
              >
                <button
                  type="button"
                  onClick={() => toggle(c.key)}
                  className="text-[11px] uppercase tracking-[0.08em] text-txt-3 hover:text-txt-1"
                >
                  {c.label}
                  {sort?.key === c.key && <span aria-hidden="true">{sort.dir === 'asc' ? ' ↑' : ' ↓'}</span>}
                </button>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {shown.length === 0 && (
            <tr>
              <td colSpan={columns.length} className="px-3 py-8 text-center text-txt-3">
                {empty}
              </td>
            </tr>
          )}
          {shown.map((row) => (
            <tr
              key={rowKey(row)}
              tabIndex={onRowClick ? 0 : undefined}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
              onKeyDown={onRowClick ? (e) => e.key === 'Enter' && onRowClick(row) : undefined}
              className={cx(
                'border-b border-line/60',
                onRowClick && 'cursor-pointer hover:bg-white/[0.03] focus-visible:bg-white/[0.05]',
              )}
            >
              {columns.map((c) => (
                <td
                  key={c.key}
                  className={cx('px-3 py-2 align-top', c.align === 'right' && 'text-right', c.mono && 'font-mono tnum')}
                >
                  {c.render ? c.render(row) : (c.value(row) ?? '–')}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
