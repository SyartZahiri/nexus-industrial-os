import type { Network } from '../../nexus/types';
import type { SceneAgv } from '../../twin/types';
import { AGV_COLOR, STATE_HEX } from '../../ui/format';
import { fitView } from './network';

/**
 * The path network seen from above: edges as lines (blocked ones in the fault colour), docks as squares, AGVs as dots
 * in their state colour. A click on an edge selects it; the same choice exists as a select list beside the map.
 */
export function NetworkMap({
  network,
  agvs,
  selected,
  onSelect,
}: {
  network: Network;
  agvs: SceneAgv[];
  selected: string | null;
  onSelect: (edgeId: string) => void;
}) {
  const v = fitView(network.nodes);
  const node = new Map(network.nodes.map((n) => [n.id, n]));
  const blocked = network.edges.filter((e) => e.blocked).length;
  return (
    <svg
      viewBox={`${v.x} ${v.y} ${v.width} ${v.height}`}
      className="h-auto w-full rounded-sm bg-ink-2"
      role="img"
      aria-label={`Wegenetz mit ${network.edges.length} Kanten, davon ${blocked} gesperrt, und ${agvs.length} AGVs`}
    >
      {network.edges.map((e) => {
        const a = node.get(e.from);
        const b = node.get(e.to);
        if (!a || !b) return null;
        const on = e.id === selected;
        return (
          <g key={e.id} onClick={() => onSelect(e.id)} className="cursor-pointer">
            <line x1={a.x} y1={a.z} x2={b.x} y2={b.z} stroke="transparent" strokeWidth={1.2} />
            <line
              x1={a.x}
              y1={a.z}
              x2={b.x}
              y2={b.z}
              stroke={e.blocked ? STATE_HEX.fault : on ? 'var(--color-txt-1)' : 'var(--color-line-2)'}
              strokeWidth={on ? 0.35 : 0.18}
              strokeDasharray={e.blocked ? '0.6 0.4' : undefined}
            />
          </g>
        );
      })}
      {network.nodes
        .filter((n) => n.dock)
        .map((n) => (
          <rect key={n.id} x={n.x - 0.35} y={n.z - 0.35} width={0.7} height={0.7} fill="var(--color-txt-3)" />
        ))}
      {agvs.map((a) => (
        <circle key={a.id} cx={a.pos.x} cy={a.pos.z} r={0.55} fill={AGV_COLOR[a.state]} stroke="var(--color-ink-1)" strokeWidth={0.15}>
          <title>{a.name}</title>
        </circle>
      ))}
    </svg>
  );
}
