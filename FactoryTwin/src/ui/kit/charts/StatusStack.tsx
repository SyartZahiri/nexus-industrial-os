import { pct, STATUS_COLOR, STATUS_SHORT } from '../../format';
import { statusShares } from './status';

/** One bar split by the time a machine spent in each status; the text alternative lists the shares. */
export function StatusStack({ timeInStatusMs, label }: { timeInStatusMs: Record<string, number>; label: string }) {
  const shares = statusShares(timeInStatusMs);
  const text = shares.length ? shares.map((s) => `${STATUS_SHORT[s.status]} ${pct(s.share)}`).join(', ') : 'keine Daten';
  return (
    <div role="img" aria-label={`${label}: ${text}`} title={text} className="flex h-2 w-full overflow-hidden rounded-sm bg-ink-3">
      {shares.map((s) => (
        <span key={s.status} style={{ width: `${s.share * 100}%`, background: STATUS_COLOR[s.status] }} />
      ))}
    </div>
  );
}
