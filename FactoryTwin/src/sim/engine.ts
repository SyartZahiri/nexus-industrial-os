import type {
  DomainEvent,
  FactoryCommands,
  NewOrderInput,
  RoutePlanner,
  TelemetryFrame,
} from '../integration/contracts';
import { EventBus } from '../integration/eventBus';
import {
  AGV_PHYSICS,
  AMBIENT_TEMP,
  CONVEYOR_POWER_PER_MPS,
  FAULT_TYPES,
  HISTORY_INTERVAL,
  HISTORY_LENGTH,
  MACHINE_PHYSICS,
  PRODUCT_IDS,
  PRODUCTS,
  THERMAL_TAU,
} from './config';
import { createInitialState } from './initialState';
import { nodePos, PATH_GRAPH } from './layout';
import { DijkstraPlanner } from './pathfinding';
import { Rng } from './rng';
import type {
  Agv,
  AgvState,
  Alarm,
  Conveyor,
  ConveyorStatus,
  Machine,
  MachineConfig,
  MachineStatus,
  Order,
  OrderPriority,
  Rules,
  Severity,
  SimState,
  TransportTarget,
  Workpiece,
} from './types';

const PRIORITY_RANK: Record<OrderPriority, number> = { eilig: 0, hoch: 1, normal: 2 };
const BASE_LOAD_KW = 14; // lighting, HVAC, compressed air
const MAX_EVENTS = 600;
const UNAVAILABLE: MachineStatus[] = ['FAULT', 'REPAIR', 'STOPPED', 'MAINTENANCE'];

export function isMachineAvailable(m: Machine): boolean {
  return !UNAVAILABLE.includes(m.status);
}

export function machineOee(m: Machine): { a: number; p: number; q: number; oee: number } {
  const t = m.stats.times;
  const total = Object.values(t).reduce((a, b) => a + b, 0);
  const planned = total - t.STOPPED - t.MAINTENANCE;
  if (planned <= 1) return { a: 1, p: 0, q: 1, oee: 0 };
  const down = t.FAULT + t.REPAIR;
  const a = Math.max(0, (planned - down) / planned);
  const avail = planned - down;
  const p = avail > 0 ? Math.min(1, t.RUNNING / avail) : 0;
  const n = m.stats.partsGood + m.stats.partsScrap;
  const q = n > 0 ? m.stats.partsGood / n : 1;
  return { a, p, q, oee: a * p * q };
}

/**
 * Holds the whole simulation state. Rendering, dashboards and adapters read `state`;
 * all changes go through the command methods.
 */
export class SimulationEngine implements FactoryCommands {
  state!: SimState;
  readonly bus = new EventBus();
  private rng!: Rng;
  private readonly localPlanner = new DijkstraPlanner(PATH_GRAPH);
  planner: RoutePlanner = this.localPlanner;
  /** Incremented on every structural change that UI snapshots should pick up. */
  revision = 0;
  private nextAlarmCheck = 0;
  private idleTimers = new Map<string, number>();

  constructor(seed = 4711, warmup = 1200) {
    this.init(seed, warmup);
  }

  /* ================================================================== */
  /* Lifecycle                                                           */
  /* ================================================================== */

  private init(seed: number, warmup: number, rules?: Rules) {
    this.rng = new Rng(seed);
    this.state = createInitialState(seed, this.rng);
    if (rules) this.state.rules = { ...rules };
    this.idleTimers.clear();
    this.nextAlarmCheck = 0;
    const s = this.state;
    // Initial order book
    const initial: [keyof typeof PRODUCTS, number, OrderPriority][] = [
      ['GH200', 10, 'normal'],
      ['AW50', 8, 'hoch'],
      ['FP12', 12, 'normal'],
      ['MT7', 6, 'normal'],
      ['GH200', 6, 'eilig'],
    ];
    for (const [product, quantity, priority] of initial) this.createOrder({ product, quantity, priority, source: 'local' }, true);
    s.nextOrderAt = 420 + this.rng.range(0, 240);

    if (warmup > 0) {
      // Run the plant for a while without logging so the app does not open on an empty hall.
      const silent = this.bus;
      (this as { bus: EventBus }).bus = new EventBus();
      for (let t = 0; t < warmup; t += 0.1) this.step(0.1);
      (this as { bus: EventBus }).bus = silent;
      s.events = [];
    }
    this.log('info', 'System', `Simulation gestartet mit Seed ${seed}, ${s.orders.length} Aufträge im Bestand`);
    this.revision++;
  }

  /** Advance by real elapsed seconds (respecting pause and speed). */
  advance(realDt: number) {
    const s = this.state;
    if (!s.running) return;
    let simDt = Math.min(realDt, 0.25) * s.speed;
    while (simDt > 1e-6) {
      const h = Math.min(0.1, simDt);
      this.step(h);
      simDt -= h;
    }
  }

  /** One deterministic simulation step. */
  step(dt: number) {
    const s = this.state;
    s.time += dt;
    this.updateOrders();
    this.updateInventory();
    for (const m of s.machines) this.updateMachine(m, dt);
    for (const c of s.conveyors) this.updateConveyor(c, dt);
    for (const a of s.agvs) this.updateAgv(a, dt);
    if (s.time >= s.nextDispatchAt) {
      this.dispatch();
      s.nextDispatchAt = s.time + 0.5;
    }
    if (s.time >= this.nextAlarmCheck) {
      this.updateAlarms();
      this.updateOrderStatus();
      this.nextAlarmCheck = s.time + 1;
    }
    this.updateEnergy(dt);
    if (s.time >= s.nextHistoryAt) {
      this.sampleHistory();
      s.nextHistoryAt += HISTORY_INTERVAL;
    }
  }

  /* ================================================================== */
  /* Logging & events                                                    */
  /* ================================================================== */

  /** Public log entry for adapters / UI (e.g. connector lifecycle). */
  note(severity: Severity, source: string, message: string) {
    this.log(severity, source, message);
    this.revision++;
  }

  private log(severity: Severity, source: string, message: string) {
    const s = this.state;
    s.events.push({ id: ++s.eventSeq, time: s.time, severity, source, message });
    if (s.events.length > MAX_EVENTS) s.events.splice(0, s.events.length - MAX_EVENTS);
    this.bus.emit({ type: 'log', time: s.time, severity, source, message });
  }

  private lastSetting: { key: string; eventId: number; at: number; from: string } | null = null;

  /**
   * Log a setting change. A slider sends a value for every pixel it moves, so changes of the same
   * setting within 1.5 s update the previous entry ("100 → 130 %") instead of adding new ones.
   */
  private logSetting(key: string, source: string, from: string, to: string, text: (from: string, to: string) => string) {
    const s = this.state;
    const now = Date.now();
    const last = this.lastSetting;
    const ev = s.events[s.events.length - 1];
    if (last && last.key === key && now - last.at < 1500 && ev && ev.id === last.eventId) {
      ev.message = text(last.from, to);
      ev.time = s.time;
      last.at = now;
      this.revision++;
      return;
    }
    this.log('info', source, text(from, to));
    this.lastSetting = { key, eventId: s.eventSeq, at: now, from };
  }

  private emit(e: DomainEvent) {
    this.bus.emit(e);
  }

  /* ================================================================== */
  /* Lookup helpers                                                      */
  /* ================================================================== */

  machine(id: string) {
    return this.state.machines.find((m) => m.id === id);
  }
  conveyor(id: string) {
    return this.state.conveyors.find((c) => c.id === id);
  }
  agv(id: string) {
    return this.state.agvs.find((a) => a.id === id);
  }
  order(id: string) {
    return this.state.orders.find((o) => o.id === id);
  }
  wp(id: string) {
    return this.state.workpieces.get(id)!;
  }

  private wpSortKey(wp: Workpiece): number {
    const o = this.order(wp.orderId);
    switch (this.state.rules.dispatchRule) {
      case 'FIFO':
        return wp.createdAt;
      case 'PRIORITY':
        return (o ? PRIORITY_RANK[o.priority] : 2) * 1e7 + wp.createdAt;
      case 'EDD':
        return o ? o.dueAt : wp.createdAt;
      case 'SPT': {
        const step = PRODUCTS[wp.product].routing[wp.step];
        return (step ? step.baseTime : 0) * 1e4 + wp.createdAt;
      }
    }
  }

  private setMachineStatus(m: Machine, to: MachineStatus) {
    if (m.status === to) return;
    const from = m.status;
    m.status = to;
    m.stats.statusSince = this.state.time;
    this.revision++;
    this.emit({ type: 'machine.status', time: this.state.time, machineId: m.id, from, to });
  }

  private setConveyorStatus(c: Conveyor, to: ConveyorStatus) {
    if (c.status === to) return;
    c.status = to;
    this.revision++;
    this.emit({ type: 'conveyor.status', time: this.state.time, conveyorId: c.id, to });
  }

  private setAgvState(a: Agv, to: AgvState) {
    if (a.state === to) return;
    a.state = to;
    this.emit({ type: 'agv.state', time: this.state.time, agvId: a.id, to });
  }

  /* ================================================================== */
  /* Orders & inventory                                                  */
  /* ================================================================== */

  private wipCount(): number {
    let n = 0;
    for (const wp of this.state.workpieces.values()) {
      if (wp.location.type !== 'finished' && wp.location.type !== 'scrap') n++;
    }
    return n;
  }

  private updateOrders() {
    const s = this.state;
    if (s.rules.autoOrders && s.time >= s.nextOrderAt) {
      const open = s.orders.filter((o) => o.status !== 'ABGESCHLOSSEN' && o.status !== 'STORNIERT').length;
      if (open < 9) {
        const r = this.rng.next();
        const priority: OrderPriority = r < 0.12 ? 'eilig' : r < 0.35 ? 'hoch' : 'normal';
        this.createOrder({ product: this.rng.pick(PRODUCT_IDS), quantity: this.rng.int(5, 14), priority, source: "local" });
      }
      s.nextOrderAt = s.time + this.rng.exp(230) + 70;
    }

    // Release (CONWIP): new raw blanks enter the system only while WIP is below the limit.
    let wip = this.wipCount();
    let guard = 0;
    while (wip < s.rules.wipLimit && s.inventory.raw > 0 && guard++ < 4) {
      const candidates = s.orders.filter(
        (o) =>
          (o.status === 'GEPLANT' || o.status === 'AKTIV' || o.status === 'VERZUG' || o.status === 'BLOCKIERT') &&
          o.completed + o.inProgress < o.quantity,
      );
      if (!candidates.length) break;
      candidates.sort((a, b) => this.orderSortKey(a) - this.orderSortKey(b));
      const o = candidates[0];
      this.releaseWorkpiece(o);
      wip++;
    }
  }

  private orderSortKey(o: Order): number {
    switch (this.state.rules.dispatchRule) {
      case 'FIFO':
        return o.createdAt;
      case 'PRIORITY':
        return PRIORITY_RANK[o.priority] * 1e7 + o.createdAt;
      case 'EDD':
        return o.dueAt;
      case 'SPT':
        return PRODUCTS[o.product].routing.reduce((a, r) => a + r.baseTime, 0) * 1e4 + o.createdAt;
    }
  }

  private releaseWorkpiece(o: Order) {
    const s = this.state;
    const id = `WP-${String(++s.counters.wp).padStart(4, '0')}`;
    const wp: Workpiece = {
      id,
      orderId: o.id,
      product: o.product,
      step: 0,
      location: { type: 'raw' },
      createdAt: s.time,
      finishedAt: null,
      defectRisk: 0.006,
      riskSources: [],
      visits: [],
      target: null,
      scrapped: false,
    };
    s.workpieces.set(id, wp);
    s.rawQueue.push(id);
    s.inventory.raw--;
    o.released++;
    o.inProgress++;
    if (o.startedAt === null) {
      if (o.status === 'GEPLANT') o.status = 'AKTIV';
      o.startedAt = s.time;
      this.log('info', o.id, `Auftrag freigegeben: ${o.quantity} × ${PRODUCTS[o.product].shortName}`);
    }
    this.revision++;
  }

  private updateInventory() {
    const s = this.state;
    const inv = s.inventory;
    if (s.rules.autoReplenish && !inv.pendingDelivery && inv.raw <= inv.reorderPoint) {
      this.orderRawMaterial(inv.reorderQty, true);
    }
    if (inv.pendingDelivery && s.time >= inv.pendingDelivery.arrivesAt) {
      const qty = Math.min(inv.pendingDelivery.qty, inv.rawCapacity - inv.raw);
      inv.raw += qty;
      inv.pendingDelivery = null;
      this.log('success', 'Wareneingang', `Rohmaterial-Lieferung eingelagert: +${qty} Rohteile (Bestand ${inv.raw})`);
      this.revision++;
    }
    for (let i = s.pendingShipments.length - 1; i >= 0; i--) {
      const sh = s.pendingShipments[i];
      if (s.time < sh.at) continue;
      s.pendingShipments.splice(i, 1);
      const o = this.order(sh.orderId);
      if (!o) continue;
      const qty = Math.min(o.completed, inv.finished[o.product]);
      inv.finished[o.product] -= qty;
      inv.shipped += qty;
      o.shippedAt = s.time;
      this.log('info', 'Versand', `${o.id} versendet: ${qty} × ${PRODUCTS[o.product].shortName}`);
      this.revision++;
    }
  }

  private updateOrderStatus() {
    const s = this.state;
    // Determine which orders have a workpiece whose next process has no usable machine.
    const blockedOrders = new Set<string>();
    for (const wp of s.workpieces.values()) {
      if (wp.location.type === 'finished' || wp.location.type === 'scrap') continue;
      const step = PRODUCTS[wp.product].routing[wp.step];
      if (!step) continue;
      if (!this.nextStepUsable(wp)) blockedOrders.add(wp.orderId);
    }
    for (const o of s.orders) {
      if (o.status === 'ABGESCHLOSSEN' || o.status === 'STORNIERT') continue;
      const prev = o.status;
      let next: Order['status'] = o.startedAt === null ? 'GEPLANT' : 'AKTIV';
      if (blockedOrders.has(o.id)) next = 'BLOCKIERT';
      else if (s.time > o.dueAt) next = 'VERZUG';
      if (next !== prev) {
        o.status = next;
        this.revision++;
        if (next === 'BLOCKIERT') this.log('warning', o.id, 'Auftrag blockiert, die benötigte Maschine ist nicht verfügbar');
        if (next === 'VERZUG' && prev !== 'BLOCKIERT') this.log('warning', o.id, 'Liefertermin überschritten');
        if (prev === 'BLOCKIERT' && next !== 'BLOCKIERT') this.log('info', o.id, 'Blockade aufgehoben, Auftrag läuft weiter');
      }
    }
  }

  /* ================================================================== */
  /* Machines                                                            */
  /* ================================================================== */

  private updateMachine(m: Machine, dt: number) {
    const s = this.state;
    const phys = MACHINE_PHYSICS[m.kind];
    const ov = m.config.override / 100;
    m.stats.times[m.status] += dt;
    const running = m.status === 'RUNNING';
    m.stats.utilEma += ((running ? 1 : 0) - m.stats.utilEma) * (dt / 600);

    switch (m.status) {
      case 'FAULT':
        if (s.rules.autoRepair) {
          m.repairDelay -= dt;
          if (m.repairDelay <= 0) this.beginRepair(m, false);
        }
        break;
      case 'REPAIR':
        m.repairRemaining -= dt;
        if (m.repairRemaining <= 0) {
          m.fault = null;
          this.log('success', m.id, 'Instandsetzung abgeschlossen, Anlage wieder betriebsbereit');
          this.resumeAfterDowntime(m);
        }
        break;
      case 'MAINTENANCE':
        m.maintenanceRemaining -= dt;
        m.wear = Math.max(0.02, m.wear - dt * 0.006);
        if (m.maintenanceRemaining <= 0) {
          m.wear = 0.02;
          this.log('success', m.id, 'Wartung abgeschlossen, Verschleiß zurückgesetzt');
          this.resumeAfterDowntime(m);
        }
        break;
      case 'STOPPED':
        break;
      case 'IDLE':
        if (s.rules.autoMaintenance && m.wear >= s.rules.maintenanceThreshold && !m.job) {
          this.startMaintenance(m, true);
          break;
        }
        this.tryStartJob(m);
        break;
      case 'SETUP':
        m.setupRemaining -= dt * ov;
        if (m.setupRemaining <= 0) {
          m.setupRemaining = 0;
          this.setMachineStatus(m, 'RUNNING');
        }
        break;
      case 'RUNNING': {
        const job = m.job!;
        job.progress += dt;
        m.wear = Math.min(1, m.wear + (dt / phys.lifetime) * ov * ov * (m.kind === 'cnc' && !m.config.coolant ? 1.5 : 1));
        if (s.rules.randomFaults) {
          const hot = m.sensors.temperature > m.config.tempWarn ? 2.5 : 1;
          const hazard = (1 / phys.mtbf) * (0.35 + 2.4 * m.wear * m.wear) * hot * (ov > 1 ? ov * ov : 1);
          if (this.rng.next() < hazard * dt) {
            const types = FAULT_TYPES.filter((f) => f.kinds.includes(m.kind) && f.id !== 'OVERTEMP' && f.id !== 'ESTOP');
            this.injectFault(m.id, this.rng.pick(types).id, true);
            break;
          }
        }
        if (job.progress >= job.duration) this.completeJob(m);
        break;
      }
      case 'BLOCKED':
        this.tryOutput(m);
        break;
    }

    // --- Sensor models -------------------------------------------------
    const active = m.status === 'RUNNING' || m.status === 'SETUP';
    const sens = m.sensors;
    const coolant = m.kind === 'cnc' && m.config.coolant ? phys.coolantEffect : 0;
    const heat = active ? phys.heatRun * Math.pow(ov, 2) * (m.status === 'SETUP' ? 0.35 : 1) : phys.heatIdle;
    const targetT = AMBIENT_TEMP + heat - (active ? coolant : coolant * 0.2) + m.wear * 9;
    sens.temperature += (targetT - sens.temperature) * (dt / THERMAL_TAU) + this.rng.gauss(0.015);
    const vibTarget = running ? (0.8 + 5.2 * Math.pow(m.wear, 1.6)) * ov : m.status === 'SETUP' ? 0.5 : 0.08;
    sens.vibration += (vibTarget - sens.vibration) * Math.min(1, dt * 1.5) + this.rng.gauss(0.01);
    sens.vibration = Math.max(0, sens.vibration);
    const coolantPower = m.kind === 'cnc' && m.config.coolant && active ? 1.4 : 0;
    const powerTarget =
      m.status === 'STOPPED' || m.status === 'FAULT' || m.status === 'REPAIR' || m.status === 'MAINTENANCE'
        ? phys.idlePower * 0.35
        : running
          ? phys.runPower * Math.pow(ov, 1.6) + coolantPower
          : m.status === 'SETUP'
            ? phys.idlePower + phys.runPower * 0.25
            : phys.idlePower;
    sens.power += (powerTarget - sens.power) * Math.min(1, dt * 2) + this.rng.gauss(0.02);
    sens.power = Math.max(0, sens.power);
    const spTarget = running ? phys.spindleRpm * ov : 0;
    sens.spindle += (spTarget - sens.spindle) * Math.min(1, dt * 1.2);
    m.stats.energyKWh += (sens.power * dt) / 3600;

    // Thermal protection trips the machine.
    if (sens.temperature >= m.config.tempTrip && (m.status === 'RUNNING' || m.status === 'SETUP' || m.status === 'BLOCKED')) {
      this.injectFault(m.id, 'OVERTEMP', true);
    }
  }

  private resumeAfterDowntime(m: Machine) {
    if (m.resumeStopped) {
      this.setMachineStatus(m, 'STOPPED');
      return;
    }
    if (m.job) this.setMachineStatus(m, m.job.finished ? 'BLOCKED' : m.setupRemaining > 0 ? 'SETUP' : 'RUNNING');
    else this.setMachineStatus(m, 'IDLE');
  }

  private inputConveyor(m: Machine): Conveyor | null {
    return m.input.type === 'conveyor' ? this.conveyor(m.input.conveyorId)! : null;
  }

  private tryStartJob(m: Machine) {
    const s = this.state;
    let wpId: string | null = null;
    if (m.input.type === 'buffer') {
      const free = m.inBuffer.filter((id) => !this.wp(id).target);
      if (free.length) {
        free.sort((a, b) => this.wpSortKey(this.wp(a)) - this.wpSortKey(this.wp(b)));
        wpId = free[0];
        m.inBuffer.splice(m.inBuffer.indexOf(wpId), 1);
      }
    } else {
      const cv = this.inputConveyor(m)!;
      const front = cv.items[0];
      if (front && front.s >= cv.length - 0.02) {
        // Robots only pick parts that actually need their process (QC takes everything at the end).
        const wp = this.wp(front.wpId);
        const step = PRODUCTS[wp.product].routing[wp.step];
        if (step && m.capabilities.includes(step.process)) {
          cv.items.shift();
          cv.stats.transported++;
          wpId = front.wpId;
        }
      }
    }
    if (!wpId) return;
    const wp = this.wp(wpId);
    const step = PRODUCTS[wp.product].routing[wp.step];
    const ov = m.config.override / 100;
    const slow = m.id === 'CNC-04' ? 1.12 : 1;
    wp.location = { type: 'machine', machineId: m.id };
    wp.visits.push({ machineId: m.id, process: step.process, start: s.time, end: null });
    m.job = { wpId, progress: 0, duration: (step.baseTime * slow * this.rng.range(0.96, 1.05)) / ov, finished: false };
    const phys = MACHINE_PHYSICS[m.kind];
    if (phys.setupTime > 0 && m.lastProduct && m.lastProduct !== wp.product) {
      m.setupRemaining = phys.setupTime;
      this.setMachineStatus(m, 'SETUP');
    } else {
      this.setMachineStatus(m, 'RUNNING');
    }
    m.lastProduct = wp.product;
    this.revision++;
  }

  private completeJob(m: Machine) {
    const s = this.state;
    const job = m.job!;
    const wp = this.wp(job.wpId);
    const visit = wp.visits[wp.visits.length - 1];
    if (visit) visit.end = s.time;

    if (m.kind === 'qc') {
      const reject = this.rng.next() < wp.defectRisk;
      m.lastVerdict = { ok: !reject, at: s.time };
      if (reject) {
        const cause = wp.riskSources.length ? `Ursache: ${[...new Set(wp.riskSources)].join(', ')}` : 'Ursache: Materialfehler';
        for (const src of new Set(wp.riskSources)) {
          const sm = this.machine(src);
          if (sm) sm.stats.partsScrap++;
        }
        m.stats.partsScrap++;
        m.job = null;
        this.scrapWorkpiece(wp, `Prüfung n.i.O., ${cause}`);
        this.setMachineStatus(m, 'IDLE');
        return;
      }
      m.stats.partsGood++;
    } else {
      const ov = m.config.override / 100;
      const add =
        0.003 +
        0.05 * m.wear * m.wear +
        (m.sensors.temperature > m.config.tempWarn ? 0.07 : 0) +
        (ov > 1.1 ? (ov - 1.1) * 0.25 : 0);
      wp.defectRisk += add;
      if (add > 0.025) wp.riskSources.push(m.id);
      m.stats.partsGood++;
    }
    wp.step++;
    job.finished = true;
    this.emit({ type: 'workpiece.processed', time: s.time, wpId: wp.id, machineId: m.id, step: wp.step });
    this.tryOutput(m);
  }

  private tryOutput(m: Machine) {
    const job = m.job;
    if (!job || !job.finished) return;
    const wp = this.wp(job.wpId);
    let ok = false;
    if (m.output.type === 'buffer') {
      if (m.outBuffer.length < m.output.capacity) {
        m.outBuffer.push(wp.id);
        wp.location = { type: 'outBuffer', machineId: m.id };
        ok = true;
      }
    } else {
      const cv = this.conveyor(m.output.conveyorId)!;
      if (cv.status !== 'FAULT' && this.canInsert(cv, m.output.at)) {
        this.insertOnConveyor(cv, wp.id, m.output.at);
        ok = true;
      }
    }
    if (ok) {
      m.job = null;
      this.setMachineStatus(m, 'IDLE');
      this.revision++;
    } else if (m.status !== 'BLOCKED') {
      this.setMachineStatus(m, 'BLOCKED');
    }
  }

  private scrapWorkpiece(wp: Workpiece, reason: string) {
    const s = this.state;
    wp.location = { type: 'scrap' };
    wp.scrapped = true;
    wp.finishedAt = s.time;
    wp.target = null;
    s.inventory.scrap++;
    s.metrics.totalScrap++;
    const o = this.order(wp.orderId);
    if (o) {
      o.scrapped++;
      o.inProgress--;
    }
    this.log('error', wp.id, `Ausschuss (${PRODUCTS[wp.product].shortName}, ${wp.orderId}): ${reason}`);
    this.emit({ type: 'workpiece.scrapped', time: s.time, wpId: wp.id, orderId: wp.orderId, reason });
    this.revision++;
  }

  private beginRepair(m: Machine, operator: boolean) {
    if (m.status !== 'FAULT') return;
    const ft = FAULT_TYPES.find((f) => f.id === m.fault?.typeId);
    m.repairRemaining = (ft?.repairTime ?? 120) * this.rng.range(0.85, 1.2);
    this.setMachineStatus(m, 'REPAIR');
    this.log('info', m.id, `${operator ? 'Bediener' : 'Instandhaltung'}: Instandsetzung gestartet (ca. ${Math.max(1, Math.round(m.repairRemaining / 60))} min)`);
  }

  private startMaintenance(m: Machine, auto: boolean) {
    m.maintenanceRemaining = 150 + m.wear * 120;
    this.setMachineStatus(m, 'MAINTENANCE');
    this.log('info', m.id, `${auto ? 'Vorbeugende Wartung (Regel: Verschleiß ≥ ' + Math.round(this.state.rules.maintenanceThreshold * 100) + ' %)' : 'Wartung durch Bediener'} gestartet`);
  }

  /* ================================================================== */
  /* Conveyors                                                           */
  /* ================================================================== */

  canInsert(cv: Conveyor, at: number): boolean {
    return !cv.items.some((it) => Math.abs(it.s - at) < cv.spacing);
  }

  private insertOnConveyor(cv: Conveyor, wpId: string, at: number) {
    cv.items.push({ wpId, s: at });
    cv.items.sort((a, b) => b.s - a.s);
    const wp = this.wp(wpId);
    wp.location = { type: 'conveyor', conveyorId: cv.id };
  }

  conveyorFreeSlots(cv: Conveyor): number {
    return Math.floor(cv.length / cv.spacing) - cv.items.length - cv.reserved;
  }

  private updateConveyor(c: Conveyor, dt: number) {
    if (c.status === 'REPAIR') {
      c.repairRemaining -= dt;
      if (c.repairRemaining <= 0) {
        c.fault = null;
        this.setConveyorStatus(c, 'RUNNING');
        this.log('success', c.id, 'Förderband wieder in Betrieb');
      }
    }
    if (c.status === 'FAULT' && this.state.rules.autoRepair) {
      c.repairRemaining -= dt;
      if (c.repairRemaining <= 0) {
        const ft = FAULT_TYPES.find((f) => f.id === c.fault?.typeId);
        c.repairRemaining = ft?.repairTime ?? 90;
        this.setConveyorStatus(c, 'REPAIR');
        this.log('info', c.id, 'Instandhaltung repariert das Förderband');
      }
    }
    const running = c.status === 'RUNNING';
    let moving = false;
    if (running) {
      c.beltOffset = (c.beltOffset + c.speed * dt) % 1000;
      c.stats.runTime += dt;
      for (let i = 0; i < c.items.length; i++) {
        const it = c.items[i];
        const limit = i === 0 ? c.length : c.items[i - 1].s - c.spacing;
        const ns = Math.min(it.s + c.speed * dt, limit);
        if (ns > it.s + 1e-6) moving = true;
        it.s = Math.max(it.s, ns);
      }
      if (this.state.rules.randomFaults && this.rng.next() < dt / 14400) {
        this.injectConveyorFault(c, 'BELT');
      }
    }
    const target = running ? CONVEYOR_POWER_PER_MPS * c.speed * (moving || c.items.length ? 1 : 0.55) : 0;
    c.power += (target - c.power) * Math.min(1, dt * 2);
    c.stats.energyKWh += (c.power * dt) / 3600;
  }

  private injectConveyorFault(c: Conveyor, typeId?: string) {
    if (c.status === 'FAULT' || c.status === 'REPAIR') return;
    const types = FAULT_TYPES.filter((f) => f.kinds.includes('conveyor'));
    const ft = types.find((f) => f.id === typeId) ?? this.rng.pick(types);
    c.fault = { typeId: ft.id, label: ft.label, since: this.state.time };
    c.repairRemaining = this.rng.range(20, 40);
    this.setConveyorStatus(c, 'FAULT');
    this.log('error', c.id, `Störung: ${ft.label}. Materialfluss auf ${c.name} angehalten`);
  }

  /* ================================================================== */
  /* AGVs                                                                */
  /* ================================================================== */

  private freeCharger(a: Agv): string | null {
    let best: string | null = null;
    let bestD = Infinity;
    for (const ch of this.state.chargers) {
      if (ch.occupiedBy && ch.occupiedBy !== a.id) continue;
      const d = this.planner.distance(a.currentNode, ch.node);
      if (d < bestD) {
        bestD = d;
        best = ch.id;
      }
    }
    return best;
  }

  private releaseCharger(a: Agv) {
    if (!a.chargerId) return;
    const ch = this.state.chargers.find((c) => c.id === a.chargerId);
    if (ch && ch.occupiedBy === a.id) ch.occupiedBy = null;
    a.chargerId = null;
  }

  private routeTo(a: Agv, node: string): boolean {
    const path = this.planner.plan(a.currentNode, node) ?? this.localPlanner.plan(a.currentNode, node);
    if (!path) return false;
    a.route = path.slice(1);
    a.routeTarget = node;
    return true;
  }

  private goCharge(a: Agv): boolean {
    const chId = this.freeCharger(a);
    if (!chId) return false;
    const ch = this.state.chargers.find((c) => c.id === chId)!;
    ch.occupiedBy = a.id;
    a.chargerId = chId;
    this.routeTo(a, ch.node);
    this.setAgvState(a, 'TO_CHARGE');
    return true;
  }

  private agvRank(a: Agv): number {
    return (a.load ? 0 : 10) + Number(a.id.slice(-2));
  }

  private isBlocked(a: Agv, dir: { x: number; z: number }): string | null {
    for (const o of this.state.agvs) {
      if (o === a) continue;
      const dx = o.pos.x - a.pos.x;
      const dz = o.pos.z - a.pos.z;
      const d = Math.sqrt((dx) * (dx) + (dz) * (dz));
      if (d > AGV_PHYSICS.safetyDistance || d < 1e-4) continue;
      const dot = (dx * dir.x + dz * dir.z) / d;
      if (dot < 0.55) continue;
      // Two vehicles waiting for each other at a merge: the higher ranked one goes first.
      if (o.blockedBy === a.id && this.agvRank(a) < this.agvRank(o)) continue;
      // Three or more vehicles waiting in a circle: after 6 s the one that has waited
      // longest moves on.
      if (o.blockedBy && a.blockedTime > 6 && a.blockedTime >= o.blockedTime) continue;
      return o.id;
    }
    return null;
  }

  /** Moves along the route; returns true once the final node is reached. */
  private drive(a: Agv, dt: number): boolean {
    if (!a.route.length) {
      a.speed = 0;
      return true;
    }
    const target = nodePos(a.route[0]);
    let dx = target.x - a.pos.x;
    let dz = target.z - a.pos.z;
    let dist = Math.sqrt((dx) * (dx) + (dz) * (dz));
    const dir = dist > 1e-6 ? { x: dx / dist, z: dz / dist } : { x: Math.sin(a.heading), z: Math.cos(a.heading) };
    const blocker = this.isBlocked(a, dir);
    if (blocker) {
      a.blockedBy = blocker;
      a.blockedTime += dt;
    } else {
      a.blockedBy = null;
      a.blockedTime = 0;
    }
    // Remaining distance to the final node (for braking)
    let remaining = dist;
    for (let i = 0; i < a.route.length - 1 && remaining < 6; i++) {
      const p = nodePos(a.route[i]);
      const q = nodePos(a.route[i + 1]);
      remaining += Math.sqrt((q.x - p.x) * (q.x - p.x) + (q.z - p.z) * (q.z - p.z));
    }
    // With an empty battery the vehicle crawls on so it does not block the lane.
    let vmax = a.battery <= 0.5 ? 0.3 : a.maxSpeed;
    vmax = Math.min(vmax, Math.sqrt(2 * AGV_PHYSICS.accel * Math.max(0, remaining)) + 0.08);
    // Corner slow-down
    if (a.route.length > 1 && dist < 1.2) {
      const n2 = nodePos(a.route[1]);
      const ex = n2.x - target.x;
      const ez = n2.z - target.z;
      const el = Math.sqrt((ex) * (ex) + (ez) * (ez)) || 1;
      const cos = (dir.x * ex + dir.z * ez) / el;
      if (cos < 0.9) vmax = Math.min(vmax, 0.55 + 0.6 * Math.max(0, cos));
    }
    const desired = blocker ? 0 : vmax;
    if (a.speed < desired) a.speed = Math.min(desired, a.speed + AGV_PHYSICS.accel * dt);
    else a.speed = Math.max(desired, a.speed - AGV_PHYSICS.accel * 2.5 * dt);

    let step = a.speed * dt;
    a.stats.distance += step;
    a.battery = Math.max(0, a.battery - step * AGV_PHYSICS.drainPerMeter);
    while (step > 0 && a.route.length) {
      const t = nodePos(a.route[0]);
      dx = t.x - a.pos.x;
      dz = t.z - a.pos.z;
      dist = Math.sqrt((dx) * (dx) + (dz) * (dz));
      if (dist <= step) {
        a.pos.x = t.x;
        a.pos.z = t.z;
        a.currentNode = a.route.shift()!;
        step -= dist;
      } else {
        a.pos.x += (dx / dist) * step;
        a.pos.z += (dz / dist) * step;
        step = 0;
      }
    }
    // Heading follows the direction of travel (smoothed)
    if (dist > 0.05 && a.speed > 0.02) {
      const want = Math.atan2(dx, dz);
      let diff = want - a.heading;
      while (diff > Math.PI) diff -= Math.PI * 2;
      while (diff < -Math.PI) diff += Math.PI * 2;
      a.heading += diff * Math.min(1, dt * 6);
      if (a.heading > Math.PI) a.heading -= Math.PI * 2;
      else if (a.heading < -Math.PI) a.heading += Math.PI * 2;
    }
    if (!a.route.length) {
      a.speed = 0;
      return true;
    }
    return false;
  }

  private updateAgv(a: Agv, dt: number) {
    const s = this.state;
    const busy = a.state !== 'IDLE' && a.state !== 'CHARGING' && a.state !== 'OFFLINE' && a.state !== 'TO_CHARGE';
    a.stats.busyEma += ((busy ? 1 : 0) - a.stats.busyEma) * (dt / 600);

    // Power model
    const moving = a.speed > 0.01;
    const pTarget = a.state === 'CHARGING' && a.battery < 100 ? AGV_PHYSICS.chargePower : moving ? AGV_PHYSICS.drivePower : AGV_PHYSICS.idlePower;
    a.power += (pTarget - a.power) * Math.min(1, dt * 3);
    a.stats.energyKWh += (a.power * dt) / 3600;

    if (a.fault) {
      a.speed = 0;
      if (s.rules.autoRepair) {
        a.repairRemaining -= dt;
        if (a.repairRemaining <= 0) {
          a.fault = null;
          this.log('success', a.id, 'Navigationsstörung behoben, Fahrt geht weiter');
          this.revision++;
        }
      }
      return;
    }

    switch (a.state) {
      case 'TO_PICKUP':
        if (this.drive(a, dt)) {
          a.timer = AGV_PHYSICS.loadTime;
          this.setAgvState(a, 'LOADING');
        }
        break;
      case 'LOADING':
        a.timer -= dt;
        if (a.timer <= 0) this.pickUp(a);
        break;
      case 'TO_DROP':
        if (this.drive(a, dt)) {
          a.timer = AGV_PHYSICS.loadTime;
          this.setAgvState(a, 'UNLOADING');
        }
        break;
      case 'UNLOADING':
        a.timer -= dt;
        if (a.timer <= 0) this.dropOff(a);
        break;
      case 'TO_CHARGE':
        if (this.drive(a, dt)) this.setAgvState(a, a.enabled ? 'CHARGING' : 'OFFLINE');
        break;
      case 'CHARGING':
      case 'OFFLINE':
        a.battery = Math.min(100, a.battery + AGV_PHYSICS.chargeRate * dt);
        if (a.forcedCharge && a.battery >= 95) a.forcedCharge = false;
        if (a.state === 'CHARGING' && !a.enabled) this.setAgvState(a, 'OFFLINE');
        if (a.state === 'OFFLINE' && a.enabled) this.setAgvState(a, 'CHARGING');
        break;
      case 'IDLE': {
        a.battery = Math.max(0, a.battery - AGV_PHYSICS.drainIdle * dt);
        const t = (this.idleTimers.get(a.id) ?? 0) + dt;
        this.idleTimers.set(a.id, t);
        if (!a.enabled || a.forcedCharge || a.battery < AGV_PHYSICS.lowBattery || t > 1.5) {
          if (this.goCharge(a)) {
            this.idleTimers.set(a.id, 0);
            if (a.battery < AGV_PHYSICS.lowBattery) this.log('warning', a.id, `Akku bei ${Math.round(a.battery)} %, fährt zur Ladestation ${a.chargerId}`);
          }
        }
        break;
      }
    }

    if (s.rules.randomFaults && moving && this.rng.next() < dt / 10800) {
      this.injectAgvFault(a, 'NAV');
    }
  }

  private injectAgvFault(a: Agv, typeId?: string) {
    if (a.fault) return;
    const types = FAULT_TYPES.filter((f) => f.kinds.includes('agv'));
    const ft = types.find((f) => f.id === typeId) ?? this.rng.pick(types);
    a.fault = { typeId: ft.id, label: ft.label, since: this.state.time };
    a.repairRemaining = ft.repairTime * this.rng.range(0.7, 1.3);
    this.log('error', a.id, `Störung: ${ft.label}. Fahrzeug steht`);
    this.revision++;
  }

  private dropNode(target: TransportTarget): string {
    if (target.type === 'finished') return 'D_FG';
    const m = this.machine(target.machineId)!;
    if (m.dockNode) return m.dockNode;
    return this.inputConveyor(m)!.dockNode!;
  }

  private pickUp(a: Agv) {
    const s = this.state;
    const task = a.task!;
    if (task.target.type === 'finished' && !this.claimOutfeedFront(a)) return;
    const wp = this.wp(task.wpId);
    const loc = wp.location;
    let ok = true;
    if (loc.type === 'raw') {
      const i = s.rawQueue.indexOf(wp.id);
      if (i >= 0) s.rawQueue.splice(i, 1);
    } else if (loc.type === 'outBuffer' || loc.type === 'inBuffer') {
      const m = this.machine(loc.machineId)!;
      const buf = loc.type === 'outBuffer' ? m.outBuffer : m.inBuffer;
      const i = buf.indexOf(wp.id);
      if (i >= 0) buf.splice(i, 1);
      else ok = false;
    } else if (loc.type === 'conveyor') {
      const cv = this.conveyor(loc.conveyorId)!;
      const i = cv.items.findIndex((it) => it.wpId === wp.id);
      if (i >= 0) {
        cv.items.splice(i, 1);
        cv.stats.transported++;
      } else ok = false;
    } else ok = false;

    if (!ok) {
      this.cancelTask(a);
      return;
    }
    wp.location = { type: 'agv', agvId: a.id };
    a.load = wp.id;
    this.routeTo(a, task.toNode);
    this.setAgvState(a, 'TO_DROP');
    this.revision++;
  }

  /**
   * At the outfeed only the front part can be lifted. Finished parts are interchangeable for the
   * trip to the warehouse, so the vehicle at the dock takes the front part and swaps assignments
   * with whichever task held it. Returns false while the vehicle has to wait.
   */
  private claimOutfeedFront(a: Agv): boolean {
    const task = a.task!;
    const cv = this.conveyor('CV-04')!;
    const wp = this.wp(task.wpId);
    if (wp.location.type !== 'conveyor' || wp.location.conveyorId !== cv.id) return true;
    const front = cv.items[0];
    if (!front || front.s < cv.length - 0.05) {
      a.timer = 0.3;
      a.waitCount = (a.waitCount ?? 0) + 1;
      if (a.waitCount > 120 && cv.status !== 'RUNNING') {
        a.waitCount = 0;
        this.cancelTask(a);
      }
      return false;
    }
    a.waitCount = 0;
    if (front.wpId !== wp.id) {
      const other = this.state.tasks.find((t) => t.wpId === front.wpId);
      const frontWp = this.wp(front.wpId);
      if (other) other.wpId = wp.id;
      else {
        wp.target = null;
        frontWp.target = task.target;
      }
      task.wpId = front.wpId;
    }
    return true;
  }

  private dropOff(a: Agv) {
    const s = this.state;
    const task = a.task!;
    const wp = this.wp(task.wpId);
    const target = task.target;
    if (target.type === 'finished') {
      wp.location = { type: 'finished' };
      wp.finishedAt = s.time;
      wp.target = null;
      s.inventory.finished[wp.product]++;
      s.metrics.totalGood++;
      s.metrics.completedTimes.push(s.time);
      const lead = s.time - wp.createdAt;
      s.metrics.leadTimes.push(lead);
      if (s.metrics.leadTimes.length > 200) s.metrics.leadTimes.shift();
      const o = this.order(wp.orderId);
      if (o) {
        o.completed++;
        o.inProgress--;
        if (o.completed >= o.quantity && o.status !== 'ABGESCHLOSSEN' && o.status !== 'STORNIERT') {
          o.status = 'ABGESCHLOSSEN';
          o.completedAt = s.time;
          const late = s.time > o.dueAt;
          this.log(late ? 'warning' : 'success', o.id, `Auftrag abgeschlossen: ${o.quantity} × ${PRODUCTS[o.product].shortName}, ${late ? 'verspätet' : 'termingerecht'}`);
          this.emit({ type: 'order.completed', time: s.time, orderId: o.id });
          s.pendingShipments.push({ orderId: o.id, at: s.time + 90 });
        }
      }
      this.emit({ type: 'workpiece.completed', time: s.time, wpId: wp.id, orderId: wp.orderId, leadTime: lead });
    } else {
      const m = this.machine(target.machineId)!;
      if (m.input.type === 'buffer') {
        m.inBuffer.push(wp.id);
        m.reservedIn = Math.max(0, m.reservedIn - 1);
        wp.location = { type: 'inBuffer', machineId: m.id };
      } else {
        const cv = this.inputConveyor(m)!;
        if (cv.status === 'FAULT' || cv.status === 'REPAIR' || !this.canInsert(cv, 0)) {
          a.timer = 0.5; // wait for space on the belt
          return;
        }
        cv.reserved = Math.max(0, cv.reserved - 1);
        this.insertOnConveyor(cv, wp.id, 0);
      }
      wp.target = null;
    }
    a.load = null;
    a.stats.tasks++;
    this.emit({ type: 'transport.completed', time: s.time, taskId: task.id, agvId: a.id, wpId: wp.id });
    s.tasks = s.tasks.filter((t) => t.id !== task.id);
    a.task = null;
    this.idleTimers.set(a.id, 0);
    this.setAgvState(a, 'IDLE');
    this.revision++;
  }

  private releaseReservation(target: TransportTarget) {
    if (target.type !== 'machine') return;
    const m = this.machine(target.machineId)!;
    if (m.input.type === 'buffer') m.reservedIn = Math.max(0, m.reservedIn - 1);
    else {
      const cv = this.inputConveyor(m)!;
      cv.reserved = Math.max(0, cv.reserved - 1);
    }
  }

  private reserve(target: TransportTarget) {
    if (target.type !== 'machine') return;
    const m = this.machine(target.machineId)!;
    if (m.input.type === 'buffer') m.reservedIn++;
    else this.inputConveyor(m)!.reserved++;
  }

  private cancelTask(a: Agv) {
    const task = a.task;
    if (!task) return;
    const wp = this.state.workpieces.get(task.wpId);
    if (wp) wp.target = null;
    this.releaseReservation(task.target);
    this.state.tasks = this.state.tasks.filter((t) => t.id !== task.id);
    a.task = null;
    this.setAgvState(a, 'IDLE');
  }

  /* ================================================================== */
  /* Dispatching & rescheduling                                          */
  /* ================================================================== */

  /** Machines allowed for the workpiece's next step under the current rule set. */
  private candidateMachines(wp: Workpiece): Machine[] {
    const step = PRODUCTS[wp.product].routing[wp.step];
    if (!step) return [];
    if (!this.state.rules.dynamicRouting) return [this.machine(step.preferred)!];
    return this.state.machines.filter((m) => m.capabilities.includes(step.process));
  }

  /**
   * Can the workpiece's next step happen at all? Parts that already sit on a machine's infeed
   * belt (or inside a machine) are physically bound to that machine.
   */
  private nextStepUsable(wp: Workpiece): boolean {
    const loc = wp.location;
    if (loc.type === 'machine') return isMachineAvailable(this.machine(loc.machineId)!);
    if (loc.type === 'conveyor') {
      const consumer = this.state.machines.find((m) => m.input.type === 'conveyor' && m.input.conveyorId === loc.conveyorId);
      if (consumer) return isMachineAvailable(consumer) && this.conveyor(loc.conveyorId)!.status !== 'FAULT';
    }
    if (loc.type === 'inBuffer' && !this.state.rules.dynamicRouting) return isMachineAvailable(this.machine(loc.machineId)!);
    return this.candidateMachines(wp).some((m) => isMachineAvailable(m));
  }

  private hasCapacity(m: Machine): boolean {
    if (m.input.type === 'buffer') return m.inBuffer.length + m.reservedIn < m.input.capacity;
    const cv = this.inputConveyor(m)!;
    return cv.status === 'RUNNING' && this.conveyorFreeSlots(cv) > 0;
  }

  private machineLoad(m: Machine, wp: Workpiece): number {
    let load = m.job ? 1 - Math.min(1, m.job.progress / m.job.duration) : 0;
    if (m.input.type === 'buffer') load += m.inBuffer.length + m.reservedIn;
    else {
      const cv = this.inputConveyor(m)!;
      load += cv.items.length + cv.reserved;
    }
    if (m.lastProduct && m.lastProduct !== wp.product && m.kind === 'cnc') load += 0.4;
    if (m.id === 'CNC-04') load += 0.35;
    if (m.status === 'BLOCKED') load += 1;
    return load;
  }

  /** A machine can currently accept deliveries (machine available and its infeed running). */
  private targetUsable(m: Machine): boolean {
    if (!isMachineAvailable(m)) return false;
    return m.input.type === 'buffer' || this.inputConveyor(m)!.status === 'RUNNING';
  }

  chooseDestination(wp: Workpiece, exclude?: string): TransportTarget | null {
    if (wp.step >= PRODUCTS[wp.product].routing.length) return { type: 'finished' };
    const dynamic = this.state.rules.dynamicRouting;
    const cands = this.candidateMachines(wp).filter(
      (m) => m.id !== exclude && (dynamic ? this.targetUsable(m) : true) && this.hasCapacity(m),
    );
    if (!cands.length) return null;
    cands.sort((a, b) => this.machineLoad(a, wp) - this.machineLoad(b, wp));
    return { type: 'machine', machineId: cands[0].id };
  }

  private dispatch() {
    const s = this.state;
    const dynamic = s.rules.dynamicRouting;

    // 1) Rescheduling of loads already en route to a machine that became unavailable.
    if (dynamic) {
      for (const a of s.agvs) {
        if ((a.state !== 'TO_DROP' && a.state !== 'UNLOADING') || !a.task || a.task.target.type !== 'machine') continue;
        const m = this.machine(a.task.target.machineId)!;
        if (this.targetUsable(m)) continue;
        const wp = this.wp(a.task.wpId);
        const alt = this.chooseDestination(wp, m.id);
        if (!alt || alt.type !== 'machine') continue;
        this.releaseReservation(a.task.target);
        this.reserve(alt);
        a.task.target = alt;
        a.task.toNode = this.dropNode(alt);
        wp.target = alt;
        if (a.state === 'UNLOADING') {
          this.routeTo(a, a.task.toNode);
          this.setAgvState(a, 'TO_DROP');
        } else {
          // Re-plan from the next node on the route.
          const from = a.route[0] ?? a.currentNode;
          const path = this.planner.plan(from, a.task.toNode);
          if (path) a.route = path;
        }
        this.log('warning', 'Umplanung', `${wp.id} unterwegs auf ${a.id} umgeleitet: ${m.id} → ${alt.machineId}`);
        this.emit({ type: 'order.rerouted', time: s.time, wpId: wp.id, fromMachine: m.id, toMachine: alt.machineId });
        this.revision++;
      }
    }

    // 2) Collect transport requests.
    type Req = { wp: Workpiece; from: string; cls: number; key: number; reroute?: string };
    const reqs: Req[] = [];
    for (const m of s.machines) {
      if (m.dockNode) {
        for (const id of m.outBuffer) {
          const wp = this.wp(id);
          if (!wp.target) reqs.push({ wp, from: m.dockNode, cls: 0, key: this.wpSortKey(wp) });
        }
        if (dynamic && !isMachineAvailable(m)) {
          for (const id of m.inBuffer) {
            const wp = this.wp(id);
            if (!wp.target) reqs.push({ wp, from: m.dockNode, cls: 1, key: this.wpSortKey(wp), reroute: m.id });
          }
        }
      }
    }
    // Outfeed: the parts queued at the end of CV-04 can be served by several vehicles at once
    // (they line up in the dock detour; the part is swapped at pickup, see pickUp()).
    const out = this.conveyor('CV-04')!;
    out.items.slice(0, 3).forEach((it, i) => {
      if (it.s < out.length - (i + 1) * out.spacing - 0.05) return;
      const wp = this.wp(it.wpId);
      if (!wp.target) reqs.push({ wp, from: out.dockNode!, cls: 0, key: -1 + i * 0.1 });
    });
    for (const id of s.rawQueue) {
      const wp = this.wp(id);
      if (!wp.target) reqs.push({ wp, from: 'D_RAW', cls: 2, key: this.wpSortKey(wp) });
    }
    if (!reqs.length) return;
    reqs.sort((a, b) => a.cls - b.cls || a.key - b.key);

    const idle = () =>
      s.agvs.filter(
        (a) =>
          a.enabled &&
          !a.fault &&
          !a.task &&
          !a.forcedCharge &&
          (a.state === 'IDLE' || (a.state === 'CHARGING' && a.battery >= AGV_PHYSICS.resumeFromCharge) || a.state === 'TO_CHARGE') &&
          a.battery >= AGV_PHYSICS.minForTask,
      );

    let free = idle();
    for (const r of reqs) {
      if (!free.length) break;
      const dest = this.chooseDestination(r.wp, r.reroute);
      if (!dest) continue;
      // Nearest vehicle (path distance through the one-way network).
      let best: Agv | null = null;
      let bestD = Infinity;
      for (const a of free) {
        const start = a.state === 'TO_CHARGE' && a.route.length ? a.route[0] : a.currentNode;
        const d = this.planner.distance(start, r.from) + (a.state === 'CHARGING' ? 2 : 0);
        if (d < bestD) {
          bestD = d;
          best = a;
        }
      }
      if (!best) break;
      let reroute = r.reroute;
      if (!reroute && dynamic && dest.type === 'machine') {
        const pref = PRODUCTS[r.wp.product].routing[r.wp.step]?.preferred;
        const pm = pref ? this.machine(pref) : undefined;
        if (pm && pm.id !== dest.machineId && !this.targetUsable(pm)) reroute = pm.id;
      }
      this.assign(best, r.wp, r.from, dest, reroute);
      free = free.filter((a) => a !== best);
    }
  }

  private assign(a: Agv, wp: Workpiece, from: string, dest: TransportTarget, reroute?: string) {
    const s = this.state;
    const task = {
      id: `TR-${String(++s.counters.task).padStart(5, '0')}`,
      wpId: wp.id,
      fromNode: from,
      toNode: this.dropNode(dest),
      target: dest,
      createdAt: s.time,
    };
    this.reserve(dest);
    wp.target = dest;
    s.tasks.push(task);
    this.releaseCharger(a);
    a.task = task;
    if (a.state === 'TO_CHARGE' && a.route.length) {
      // Continue to the next node of the current route, then re-route from there.
      const path = this.planner.plan(a.route[0], from);
      a.route = path ?? [];
    } else {
      this.routeTo(a, from);
    }
    a.routeTarget = from;
    this.setAgvState(a, 'TO_PICKUP');
    this.idleTimers.set(a.id, 0);
    if (reroute && dest.type === 'machine') {
      this.log('warning', 'Umplanung', `${wp.id} wird von ${reroute} (nicht verfügbar) nach ${dest.machineId} umgeplant`);
      this.emit({ type: 'order.rerouted', time: s.time, wpId: wp.id, fromMachine: reroute, toMachine: dest.machineId });
    }
    this.emit({ type: 'transport.assigned', time: s.time, taskId: task.id, agvId: a.id, wpId: wp.id, from, to: task.toNode });
    this.revision++;
  }

  /* ================================================================== */
  /* Alarms, energy, history                                             */
  /* ================================================================== */

  private updateAlarms() {
    const s = this.state;
    const want: Omit<Alarm, 'since' | 'acknowledged'>[] = [];
    for (const m of s.machines) {
      if (m.status === 'FAULT' || m.status === 'REPAIR')
        want.push({ key: `${m.id}:fault`, severity: 'error', source: m.id, message: `${m.status === 'REPAIR' ? 'In Instandsetzung' : 'Störung'}: ${m.fault?.label ?? ''}` });
      if (m.sensors.temperature >= m.config.tempWarn)
        want.push({ key: `${m.id}:temp`, severity: 'warning', source: m.id, message: `Temperatur hoch: ${m.sensors.temperature.toFixed(1)} °C (Grenze ${m.config.tempWarn} °C)` });
      if (m.wear >= 0.8) want.push({ key: `${m.id}:wear`, severity: 'warning', source: m.id, message: `Verschleiß ${Math.round(m.wear * 100)} %, Wartung empfohlen` });
      if (m.status === 'BLOCKED' && s.time - m.stats.statusSince > 45)
        want.push({ key: `${m.id}:blocked`, severity: 'warning', source: m.id, message: 'Ausgang belegt, Teil kann nicht übergeben werden' });
    }
    for (const c of s.conveyors) {
      if (c.status === 'FAULT' || c.status === 'REPAIR') want.push({ key: `${c.id}:fault`, severity: 'error', source: c.id, message: `Störung: ${c.fault?.label ?? ''}` });
    }
    for (const a of s.agvs) {
      if (a.fault) want.push({ key: `${a.id}:fault`, severity: 'error', source: a.id, message: `Störung: ${a.fault.label}` });
      if (a.battery < 20) want.push({ key: `${a.id}:battery`, severity: 'warning', source: a.id, message: `Akku niedrig: ${Math.round(a.battery)} %` });
    }
    if (s.inventory.raw === 0) want.push({ key: 'raw:empty', severity: 'error', source: 'Rohlager', message: 'Kein Rohmaterial mehr, Aufträge können nicht freigegeben werden' });
    else if (s.inventory.raw <= s.inventory.reorderPoint)
      want.push({ key: 'raw:low', severity: 'warning', source: 'Rohlager', message: `Rohmaterial unter Meldebestand (${s.inventory.raw})` });
    for (const o of s.orders) {
      if (o.status === 'BLOCKIERT') want.push({ key: `${o.id}:blocked`, severity: 'error', source: o.id, message: 'Auftrag blockiert, benötigte Anlage nicht verfügbar' });
      else if (o.status === 'VERZUG') want.push({ key: `${o.id}:late`, severity: 'warning', source: o.id, message: 'Liefertermin überschritten' });
    }
    const prev = new Map(s.alarms.map((a) => [a.key, a]));
    const next: Alarm[] = want.map((w) => {
      const p = prev.get(w.key);
      if (!p && w.key.endsWith(':temp')) this.log('warning', w.source, w.message);
      return p ? { ...p, message: w.message, severity: w.severity } : { ...w, since: s.time, acknowledged: false };
    });
    if (next.length !== s.alarms.length || next.some((a) => !prev.has(a.key))) this.revision++;
    s.alarms = next;
  }

  private updateEnergy(dt: number) {
    const s = this.state;
    let p = BASE_LOAD_KW;
    for (const m of s.machines) p += m.sensors.power;
    for (const c of s.conveyors) p += c.power;
    for (const a of s.agvs) p += a.power;
    s.metrics.powerKW = p;
    s.metrics.energyKWh += (p * dt) / 3600;
  }

  throughput(): number {
    const s = this.state;
    const window = 3600;
    const cut = s.time - window;
    const ct = s.metrics.completedTimes;
    while (ct.length && ct[0] < cut - 1) ct.shift();
    const n = ct.filter((t) => t >= cut).length;
    return (n * 3600) / Math.max(600, Math.min(window, s.time));
  }

  overallOee(): number {
    const ms = this.state.machines;
    return ms.reduce((a, m) => a + machineOee(m).oee, 0) / ms.length;
  }

  private sampleHistory() {
    const s = this.state;
    const h = s.history;
    const push = (arr: number[], v: number) => {
      arr.push(v);
      if (arr.length > HISTORY_LENGTH) arr.shift();
    };
    const cnc = s.machines.filter((m) => m.kind === 'cnc');
    push(h.t, s.time);
    push(h.throughput, this.throughput());
    push(h.power, s.metrics.powerKW);
    push(h.avgTemp, cnc.reduce((a, m) => a + m.sensors.temperature, 0) / cnc.length);
    push(h.wip, this.wipCount());
    push(h.utilization, s.machines.reduce((a, m) => a + m.stats.utilEma, 0) / s.machines.length);
    push(h.oee, this.overallOee());
    for (const m of s.machines) {
      push(h.machineTemp[m.id], m.sensors.temperature);
      push(h.machinePower[m.id], m.sensors.power);
    }
    // Forget finished workpieces after 15 minutes (counts are kept in metrics / inventory).
    for (const [id, wp] of s.workpieces) {
      if (wp.finishedAt !== null && s.time - wp.finishedAt > 900) s.workpieces.delete(id);
    }
    this.revision++;
  }

  getTelemetry(): TelemetryFrame {
    const s = this.state;
    const scrap = s.metrics.totalScrap;
    return {
      time: s.time,
      machines: s.machines.map((m) => ({
        id: m.id,
        status: m.status,
        temperature: m.sensors.temperature,
        power: m.sensors.power,
        vibration: m.sensors.vibration,
        wear: m.wear,
        utilization: m.stats.utilEma,
      })),
      agvs: s.agvs.map((a) => ({ id: a.id, state: a.state, battery: a.battery, x: a.pos.x, z: a.pos.z })),
      kpi: {
        throughput: this.throughput(),
        oee: this.overallOee(),
        powerKW: s.metrics.powerKW,
        energyKWh: s.metrics.energyKWh,
        wip: this.wipCount(),
        scrapRate: scrap / Math.max(1, scrap + s.metrics.totalGood),
      },
    };
  }

  /* ================================================================== */
  /* Commands                                                            */
  /* ================================================================== */

  play() {
    if (this.state.running) return;
    this.state.running = true;
    this.log('info', 'Simulation', 'Simulation fortgesetzt');
    this.revision++;
  }

  pause() {
    if (!this.state.running) return;
    this.state.running = false;
    this.log('info', 'Simulation', 'Simulation pausiert');
    this.revision++;
  }

  setSpeed(speed: number) {
    this.state.speed = Math.max(0.25, Math.min(50, speed));
    this.revision++;
  }

  reset(seed?: number) {
    const running = this.state.running;
    const speed = this.state.speed;
    const rules = this.state.rules;
    this.init(seed ?? this.state.seed, 1200, rules);
    this.state.running = running;
    this.state.speed = speed;
    this.log('info', 'Simulation', 'Simulation zurückgesetzt');
  }

  startMachine(id: string) {
    const m = this.machine(id);
    if (!m) return;
    m.resumeStopped = false;
    if (m.status !== 'STOPPED') return;
    this.log('info', m.id, 'Bediener: Anlage gestartet');
    this.resumeAfterDowntime(m);
  }

  stopMachine(id: string) {
    const m = this.machine(id);
    if (!m) return;
    if (m.status === 'FAULT' || m.status === 'REPAIR' || m.status === 'MAINTENANCE') {
      m.resumeStopped = true;
      this.log('info', m.id, 'Bediener: Anlage bleibt nach Instandsetzung gestoppt');
      return;
    }
    if (m.status === 'STOPPED') return;
    this.setMachineStatus(m, 'STOPPED');
    m.resumeStopped = true;
    this.log('warning', m.id, `Bediener: Anlage gestoppt${m.job && !m.job.finished ? ', laufende Bearbeitung pausiert' : ''}`);
  }

  injectFault(id: string, faultTypeId?: string, automatic = false) {
    const m = this.machine(id);
    if (!m) {
      const c = this.conveyor(id);
      if (c) return this.injectConveyorFault(c, faultTypeId);
      const a = this.agv(id);
      if (a) return this.injectAgvFault(a, faultTypeId);
      return;
    }
    if (m.status === 'FAULT' || m.status === 'REPAIR') return;
    const types = FAULT_TYPES.filter((f) => f.kinds.includes(m.kind));
    const ft = types.find((f) => f.id === faultTypeId) ?? this.rng.pick(types);
    m.fault = { typeId: ft.id, label: ft.label, since: this.state.time };
    m.stats.faults++;
    m.repairDelay = this.rng.range(15, 35);
    if (m.status === 'MAINTENANCE') m.maintenanceRemaining = 0;
    const wasStopped = m.status === 'STOPPED';
    if (m.job && !m.job.finished && ft.scrapsWorkpiece) {
      const wp = this.wp(m.job.wpId);
      m.job = null;
      m.stats.partsScrap++;
      this.scrapWorkpiece(wp, `${ft.label} an ${m.id}`);
    }
    m.resumeStopped = wasStopped;
    this.setMachineStatus(m, 'FAULT');
    this.log('error', m.id, `${automatic ? 'Störung' : 'Störung vom Bediener ausgelöst'}: ${ft.label}`);
    this.emit({ type: 'machine.fault', time: this.state.time, machineId: m.id, fault: ft.label, scrapped: ft.scrapsWorkpiece });
    if (this.state.rules.dynamicRouting) {
      const affected = m.inBuffer.length;
      if (affected || m.input.type === 'conveyor')
        this.log('info', 'Umplanung', `Dynamische Umplanung: ${affected ? affected + ' wartende Teile gehen' : 'neue Zuführungen gehen'} an Ausweichanlagen`);
    } else {
      this.log('warning', 'Umplanung', `Statischer Arbeitsplan: Teile für ${m.id} warten bis zur Instandsetzung`);
    }
  }

  repair(id: string) {
    const m = this.machine(id);
    if (m) {
      if (m.status === 'FAULT') this.beginRepair(m, true);
      return;
    }
    const c = this.conveyor(id);
    if (c && c.status === 'FAULT') {
      const ft = FAULT_TYPES.find((f) => f.id === c.fault?.typeId);
      c.repairRemaining = ft?.repairTime ?? 90;
      this.setConveyorStatus(c, 'REPAIR');
      this.log('info', c.id, 'Bediener: Reparatur Förderband gestartet');
      return;
    }
    const a = this.agv(id);
    if (a && a.fault) {
      a.fault = null;
      this.log('success', a.id, 'Bediener: Störung quittiert, Fahrzeug neu lokalisiert');
      this.revision++;
    }
  }

  maintain(id: string) {
    const m = this.machine(id);
    if (!m) return;
    if (m.status === 'FAULT' || m.status === 'REPAIR' || m.status === 'MAINTENANCE') return;
    this.startMaintenance(m, false);
  }

  configureMachine(id: string, patch: Partial<MachineConfig>) {
    const m = this.machine(id);
    if (!m) return;
    const before = { ...m.config };
    m.config = { ...m.config, ...patch };
    m.config.override = Math.max(50, Math.min(130, Math.round(m.config.override)));
    if (patch.tempTrip !== undefined && patch.tempWarn === undefined) m.config.tempWarn = Math.min(m.config.tempWarn, m.config.tempTrip - 4);
    else m.config.tempTrip = Math.max(m.config.tempWarn + 4, m.config.tempTrip);
    if (patch.override !== undefined && m.job && !m.job.finished) {
      // Remaining work is rescaled with the new feed override.
      const remaining = m.job.duration - m.job.progress;
      m.job.duration = m.job.progress + (remaining * before.override) / m.config.override;
    }
    const c = m.config;
    if (before.override !== c.override)
      this.logSetting(`${m.id}:override`, m.id, String(before.override), String(c.override), (a, b) => `Override ${a} → ${b} %`);
    if (before.coolant !== c.coolant) this.log('info', m.id, `Kühlmittel ${c.coolant ? 'ein' : 'aus'}`);
    if (before.tempWarn !== c.tempWarn)
      this.logSetting(`${m.id}:warn`, m.id, String(before.tempWarn), String(c.tempWarn), (a, b) => `Warngrenze ${a} → ${b} °C`);
    if (before.tempTrip !== c.tempTrip)
      this.logSetting(`${m.id}:trip`, m.id, String(before.tempTrip), String(c.tempTrip), (a, b) => `Abschaltgrenze ${a} → ${b} °C`);
    this.revision++;
  }

  startConveyor(id: string) {
    const c = this.conveyor(id);
    if (!c || c.status !== 'STOPPED') return;
    this.setConveyorStatus(c, 'RUNNING');
    this.log('info', c.id, 'Bediener: Förderband gestartet');
  }

  stopConveyor(id: string) {
    const c = this.conveyor(id);
    if (!c || c.status !== 'RUNNING') return;
    this.setConveyorStatus(c, 'STOPPED');
    this.log('warning', c.id, 'Bediener: Förderband gestoppt');
  }

  setConveyorSpeed(id: string, speed: number) {
    const c = this.conveyor(id);
    if (!c) return;
    const before = c.speed;
    c.speed = Math.round(Math.max(0.1, Math.min(1.2, speed)) * 100) / 100;
    if (c.speed !== before) {
      const f = (v: number) => v.toLocaleString('de-DE', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
      this.logSetting(`${c.id}:speed`, c.id, f(before), f(c.speed), (a, b) => `Bandgeschwindigkeit ${a} → ${b} m/s`);
    }
    this.revision++;
  }

  setAgvEnabled(id: string, enabled: boolean) {
    const a = this.agv(id);
    if (!a || a.enabled === enabled) return;
    a.enabled = enabled;
    this.log(enabled ? 'info' : 'warning', a.id, enabled ? 'Bediener: Fahrzeug wieder in Betrieb' : 'Bediener: Fahrzeug außer Betrieb (nach aktuellem Transport)');
    this.revision++;
  }

  sendAgvToCharge(id: string) {
    const a = this.agv(id);
    if (!a) return;
    a.forcedCharge = true;
    this.log('info', a.id, 'Bediener: Ladevorgang angefordert (nach aktuellem Transport)');
    this.revision++;
  }

  createOrder(input: NewOrderInput, silent = false): string {
    const s = this.state;
    const id = `FA-${String(1000 + ++s.counters.order)}`;
    const routingTime = PRODUCTS[input.product].routing.reduce((a, r) => a + r.baseTime, 0);
    const slack = input.priority === 'eilig' ? 1.4 : input.priority === 'hoch' ? 2.0 : 2.8;
    const dueIn = input.dueIn ?? Math.round(routingTime * 2 + input.quantity * 65 * slack + this.rng.range(300, 900));
    const o: Order = {
      id,
      product: input.product,
      quantity: Math.max(1, Math.min(60, Math.round(input.quantity))),
      priority: input.priority,
      createdAt: s.time,
      dueAt: s.time + dueIn,
      released: 0,
      completed: 0,
      scrapped: 0,
      inProgress: 0,
      status: 'GEPLANT',
      startedAt: null,
      completedAt: null,
      shippedAt: null,
      source: input.source ?? 'operator',
    };
    s.orders.push(o);
    // Keep the order book compact: drop shipped orders beyond the latest 40.
    const done = s.orders.filter((x) => x.status === 'ABGESCHLOSSEN' || x.status === 'STORNIERT');
    if (done.length > 30) {
      const drop = new Set(done.slice(0, done.length - 30).map((x) => x.id));
      s.orders = s.orders.filter((x) => !drop.has(x.id));
    }
    if (!silent) {
      const src = o.source === 'smartfactory' ? 'SmartFactory' : o.source === 'operator' ? 'Bediener' : 'ERP';
      this.log('info', o.id, `Neuer Auftrag (${src}): ${o.quantity} × ${PRODUCTS[o.product].shortName}, Priorität ${o.priority}`);
    }
    this.emit({ type: 'order.created', time: s.time, orderId: id, product: o.product, quantity: o.quantity });
    this.revision++;
    return id;
  }

  cancelOrder(id: string) {
    const o = this.order(id);
    if (!o || o.status === 'ABGESCHLOSSEN' || o.status === 'STORNIERT') return;
    o.status = 'STORNIERT';
    // Unreleased blanks remain in stock; parts already in production are finished as stock.
    for (const wpId of [...this.state.rawQueue]) {
      const wp = this.wp(wpId);
      if (wp.orderId === id && !wp.target) {
        this.state.rawQueue.splice(this.state.rawQueue.indexOf(wpId), 1);
        this.state.workpieces.delete(wpId);
        this.state.inventory.raw++;
        o.released--;
        o.inProgress--;
      }
    }
    this.log('warning', o.id, 'Auftrag storniert');
    this.revision++;
  }

  setOrderPriority(id: string, priority: OrderPriority) {
    const o = this.order(id);
    if (!o || o.priority === priority) return;
    o.priority = priority;
    this.log('info', o.id, `Priorität geändert → ${priority}`);
    this.revision++;
  }

  setRules(patch: Partial<Rules>) {
    const s = this.state;
    const labels: Record<keyof Rules, string> = {
      dynamicRouting: 'Dynamische Umplanung',
      dispatchRule: 'Reihenfolgeregel',
      randomFaults: 'Zufallsstörungen',
      autoMaintenance: 'Vorbeugende Wartung',
      maintenanceThreshold: 'Wartungsschwelle',
      autoRepair: 'Automatische Instandsetzung',
      wipLimit: 'WIP-Limit',
      autoOrders: 'Automatischer Auftragseingang',
      autoReplenish: 'Automatische Nachbestellung',
    };
    for (const k of Object.keys(patch) as (keyof Rules)[]) {
      const v = patch[k];
      if (v === undefined || s.rules[k] === v) continue;
      (s.rules as unknown as Record<string, unknown>)[k] = v;
      const shown = typeof v === 'boolean' ? (v ? 'aktiv' : 'inaktiv') : k === 'maintenanceThreshold' ? `${Math.round(Number(v) * 100)} %` : String(v);
      this.logSetting(`rule:${k}`, 'Regelwerk', '', shown, (_a, b) => `${labels[k]}: ${b}`);
    }
    this.revision++;
  }

  orderRawMaterial(qty: number, auto = false) {
    const inv = this.state.inventory;
    if (inv.pendingDelivery) {
      inv.pendingDelivery.qty += qty;
    } else {
      inv.pendingDelivery = { qty, arrivesAt: this.state.time + 240 };
    }
    this.log('info', 'Einkauf', `${auto ? 'Meldebestand erreicht, ' : 'Bediener: '}${qty} Rohteile bestellt (Lieferung in ca. 4 min)`);
    this.revision++;
  }

  acknowledgeAlarm(key: string) {
    const a = this.state.alarms.find((x) => x.key === key);
    if (!a || a.acknowledged) return;
    a.acknowledged = true;
    this.revision++;
  }

  setRoutePlanner(planner: RoutePlanner | null) {
    this.planner = planner ?? this.localPlanner;
    this.log('info', 'Integration', `Routenplaner: ${this.planner.label}`);
    this.revision++;
  }

}
