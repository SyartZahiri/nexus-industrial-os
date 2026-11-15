/** The machine a link names. Before the first frame there are no machines, and no id counts as unknown. */
export function pickMachine<M extends { id: string }>(machines: M[], id: string | null): { machine: M | null; unknown: string | null } {
  if (id === null || machines.length === 0) return { machine: null, unknown: null };
  const machine = machines.find((m) => m.id === id) ?? null;
  return { machine, unknown: machine ? null : id };
}
