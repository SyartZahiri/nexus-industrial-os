import type { Command, Rules } from '../../nexus/types';

/** The setRules command with only the changed fields, or null when nothing changed. */
export function rulesPatch(current: Rules, edited: Rules): Command | null {
  const changed = (Object.keys(edited) as (keyof Rules)[]).filter((k) => edited[k] !== current[k]);
  if (changed.length === 0) return null;
  return { command: 'setRules', ...Object.fromEntries(changed.map((k) => [k, edited[k]])) };
}

/**
 * Sends the change and returns the rules the core then reports. If the core refuses (send shows its reason), the
 * form falls back to the valid values instead of keeping one the plant does not run with.
 */
export async function applyRules(
  current: Rules,
  edited: Rules,
  send: (c: Command) => Promise<boolean>,
  reload: () => Promise<Rules | null>,
): Promise<Rules> {
  const patch = rulesPatch(current, edited);
  if (patch) await send(patch);
  return (await reload()) ?? current;
}
