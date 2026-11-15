import type { AgvState, MachineStatus } from '../../twin/types';
import { AGV_COLOR, AGV_LABEL, stateOf, STATUS_COLOR, STATUS_SHORT, type StateMap } from '../format';

/** A state as dot and word; the colour never stands alone. */
export function StateTag(props: { kind: 'machine'; status: MachineStatus } | { kind: 'agv'; status: AgvState }) {
  const color = props.kind === 'machine' ? STATUS_COLOR[props.status] : AGV_COLOR[props.status];
  const text = props.kind === 'machine' ? STATUS_SHORT[props.status] : AGV_LABEL[props.status];
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap text-[12px] text-txt-1">
      <span className="h-1.5 w-1.5 shrink-0 rounded-full" style={{ background: color }} aria-hidden="true" />
      {text}
    </span>
  );
}

/** A core code (order, operation, transport) as dot and word. */
export function CodeTag({ map, code }: { map: StateMap; code: string }) {
  const s = stateOf(map, code);
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap text-[12px] text-txt-1">
      <span className="h-1.5 w-1.5 shrink-0 rounded-full" style={{ background: s.color }} aria-hidden="true" />
      {s.text}
    </span>
  );
}
