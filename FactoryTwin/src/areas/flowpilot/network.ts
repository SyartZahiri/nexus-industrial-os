import type { Command, NetworkEdge, NetworkNode } from '../../nexus/types';

/** SVG viewBox around all nodes (hall metres, z downwards), with pad metres of margin. */
export function fitView(nodes: NetworkNode[], pad = 2) {
  if (nodes.length === 0) return { x: 0, y: 0, width: 1, height: 1 };
  const xs = nodes.map((n) => n.x);
  const zs = nodes.map((n) => n.z);
  const x = Math.min(...xs) - pad;
  const y = Math.min(...zs) - pad;
  return { x, y, width: Math.max(...xs) + pad - x, height: Math.max(...zs) + pad - y };
}

export function edgeAction(e: NetworkEdge): { command: Command; label: string } {
  return e.blocked
    ? { command: { command: 'edgeUnblock', edgeId: e.id }, label: 'Freigeben' }
    : { command: { command: 'edgeBlock', edgeId: e.id }, label: 'Sperren' };
}
