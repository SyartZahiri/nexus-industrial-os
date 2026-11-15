import { describe, expect, it } from 'vitest';
import { edgeAction, fitView } from './network';

describe('network', () => {
  it('frames the nodes with a margin', () => {
    expect(fitView([])).toEqual({ x: 0, y: 0, width: 1, height: 1 });
    expect(
      fitView([
        { id: 'a', x: -20, z: -3, dock: false },
        { id: 'b', x: 20, z: 3, dock: true },
      ]),
    ).toEqual({ x: -22, y: -5, width: 44, height: 10 });
  });

  it('aBlockedEdgeOffersUnblock', () => {
    const edge = { id: 'E-12', from: 'a', to: 'b', length: 4, blocked: true };
    expect(edgeAction(edge)).toEqual({ command: { command: 'edgeUnblock', edgeId: 'E-12' }, label: 'Freigeben' });
    expect(edgeAction({ ...edge, blocked: false })).toEqual({ command: { command: 'edgeBlock', edgeId: 'E-12' }, label: 'Sperren' });
  });
});
