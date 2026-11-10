import { PRODUCTS } from './config';
import type { SimulationEngine } from './engine';

/** Checks that every workpiece sits in exactly one place and all bookkeeping matches. */
export function checkInvariants(e: SimulationEngine): string[] {
  const s = e.state;
  const errors: string[] = [];
  const seen = new Map<string, string>();
  const put = (id: string, where: string) => {
    if (seen.has(id)) errors.push(`${id} doppelt: ${seen.get(id)} und ${where}`);
    seen.set(id, where);
  };
  s.rawQueue.forEach((id) => put(id, 'raw'));
  for (const m of s.machines) {
    m.inBuffer.forEach((id) => put(id, `${m.id}.in`));
    m.outBuffer.forEach((id) => put(id, `${m.id}.out`));
    if (m.job) put(m.job.wpId, `${m.id}.job`);
    if (m.input.type === 'buffer' && m.inBuffer.length > m.input.capacity) errors.push(`${m.id} Eingangspuffer überfüllt`);
    if (m.output.type === 'buffer' && m.outBuffer.length > m.output.capacity) errors.push(`${m.id} Ausgangspuffer überfüllt`);
    if ((m.status === 'RUNNING' || m.status === 'SETUP' || m.status === 'BLOCKED') && !m.job) errors.push(`${m.id} ${m.status} ohne Job`);
    if (m.status === 'BLOCKED' && m.job && !m.job.finished) errors.push(`${m.id} blockiert, Job aber nicht fertig`);
    if ((m.status === 'FAULT' || m.status === 'REPAIR') && !m.fault) errors.push(`${m.id} ${m.status} ohne Störungsinfo`);
  }
  for (const c of s.conveyors) {
    for (let i = 0; i < c.items.length; i++) {
      put(c.items[i].wpId, c.id);
      if (c.items[i].s < -1e-6 || c.items[i].s > c.length + 1e-6) errors.push(`${c.id} Position außerhalb`);
      if (i > 0 && c.items[i - 1].s - c.items[i].s < c.spacing - 1e-3) errors.push(`${c.id} Abstand verletzt`);
    }
  }
  for (const a of s.agvs) {
    if (a.load) put(a.load, a.id);
    if (a.load && !a.task) errors.push(`${a.id} Ladung ohne Auftrag`);
    if (a.task && !s.tasks.includes(a.task)) errors.push(`${a.id} Auftrag nicht in Taskliste`);
    if (a.battery < 0 || a.battery > 100) errors.push(`${a.id} Akku ${a.battery}`);
    if (Math.abs(a.heading) > Math.PI + 1e-6) errors.push(`${a.id} Ausrichtung nicht normalisiert`);
  }
  if (s.tasks.length !== s.agvs.filter((a) => a.task).length) errors.push('Taskliste ≠ AGV-Aufträge');

  // Location field matches the container
  for (const wp of s.workpieces.values()) {
    const where = seen.get(wp.id);
    const t = wp.location.type;
    if (t === 'finished' || t === 'scrap') {
      if (where) errors.push(`${wp.id} ist ${t}, liegt aber in ${where}`);
      continue;
    }
    if (!where) errors.push(`${wp.id} (${t}) verschwunden`);
  }
  for (const id of seen.keys()) if (!s.workpieces.has(id)) errors.push(`${id} unbekannt`);

  // Reservations
  for (const m of s.machines) {
    const pending = s.tasks.filter((t) => t.target.type === 'machine' && t.target.machineId === m.id).length;
    if (m.input.type === 'buffer' && m.reservedIn !== pending) errors.push(`${m.id} reservedIn ${m.reservedIn} ≠ ${pending}`);
  }
  for (const c of s.conveyors) {
    const consumers = s.machines.filter((m) => m.input.type === 'conveyor' && m.input.conveyorId === c.id).map((m) => m.id);
    const pending = s.tasks.filter((t) => t.target.type === 'machine' && consumers.includes(t.target.machineId)).length;
    if (consumers.length && c.reserved !== pending) errors.push(`${c.id} reserved ${c.reserved} ≠ ${pending}`);
  }
  // Chargers
  for (const ch of s.chargers) {
    if (!ch.occupiedBy) continue;
    const a = s.agvs.find((x) => x.id === ch.occupiedBy);
    if (!a || a.chargerId !== ch.id) errors.push(`${ch.id} belegt von ${ch.occupiedBy}, Fahrzeug sieht das anders`);
  }
  // Orders
  for (const o of s.orders) {
    const live = [...s.workpieces.values()].filter((w) => w.orderId === o.id && w.location.type !== 'finished' && w.location.type !== 'scrap').length;
    if (o.inProgress !== live) errors.push(`${o.id} inProgress ${o.inProgress} ≠ ${live}`);
    if (o.completed + o.inProgress > o.quantity && o.status !== 'STORNIERT') errors.push(`${o.id} überproduziert`);
  }
  // Workpiece step sanity
  for (const wp of s.workpieces.values()) {
    if (wp.step < 0 || wp.step > PRODUCTS[wp.product].routing.length) errors.push(`${wp.id} Schritt ${wp.step}`);
  }
  return errors;
}
