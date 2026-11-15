import type { Answer, Query } from './types';

/** The key of a query within a run: after a restart the same question is a new one, old answers do not count. */
export function queryKey(q: Query, run: number): string {
  return `${run}|${JSON.stringify(q)}`;
}

/** The last answer and the query it belongs to, so a new query never shows the answer to an old one. */
export interface QueryState<T> {
  key: string;
  data: T | null;
  error: string | null;
}

/** An answer to `key`: a result replaces the data; a refusal keeps the data only if it answered the same query. */
export function answered<T>(prev: QueryState<T>, key: string, a: Answer<T>): QueryState<T> {
  if (a.ok) return { key, data: a.result, error: null };
  return { key, data: prev.key === key ? prev.data : null, error: a.message };
}

/** What a view asking `key` may show: nothing until its own query was answered. */
export function shown<T>(s: QueryState<T>, key: string): { data: T | null; error: string | null } {
  return s.key === key ? { data: s.data, error: s.error } : { data: null, error: null };
}
