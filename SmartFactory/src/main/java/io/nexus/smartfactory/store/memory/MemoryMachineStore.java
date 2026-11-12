package io.nexus.smartfactory.store.memory;

import io.nexus.contracts.DomainException;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.smartfactory.master.SmartFactoryMasterData;
import io.nexus.smartfactory.store.MachineRecord;
import io.nexus.smartfactory.store.MachineStore;
import io.nexus.smartfactory.store.StateInterval;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** MachineStore in memory, seeded with every machine of the master data in state IDLE. */
public final class MemoryMachineStore implements MachineStore {
  private final MemoryUnitOfWork work;
  private final Map<String, MachineRecord> machines = new TreeMap<>();
  private final List<StateInterval> intervals = new ArrayList<>();

  public MemoryMachineStore(MemoryUnitOfWork work) {
    this.work = work;
    for (var m : SmartFactoryMasterData.machines()) machines.put(m.id(), new MachineRecord(m.id(), MachineStatus.IDLE, null, 0));
  }

  @Override
  public List<MachineRecord> machines() {
    return List.copyOf(machines.values());
  }

  @Override
  public MachineRecord save(MachineRecord m) {
    MachineRecord stored = machines.get(m.id());
    if (stored == null) throw DomainException.notFound("Maschine", m.id());
    if (stored.version() != m.version()) {
      throw DomainException.conflict("Maschine " + m.id() + " wurde zwischenzeitlich geändert");
    }
    var next = new MachineRecord(m.id(), m.status(), m.lastProductId(), m.version() + 1);
    machines.put(m.id(), next);
    work.onRollback(() -> machines.put(m.id(), stored));
    return next;
  }

  @Override
  public void changeState(String machineId, MachineStatus status, long nowMs) {
    for (int i = 0; i < intervals.size(); i++) {
      StateInterval open = intervals.get(i);
      if (open.machineId().equals(machineId) && open.toMs() == null) {
        int index = i;
        intervals.set(index, new StateInterval(machineId, open.status(), open.fromMs(), nowMs));
        work.onRollback(() -> intervals.set(index, open));
      }
    }
    intervals.add(new StateInterval(machineId, status, nowMs, null));
    work.onRollback(() -> intervals.remove(intervals.size() - 1));
  }

  @Override
  public List<StateInterval> intervals(String machineId, long fromMs, long toMs) {
    List<StateInterval> out = new ArrayList<>();
    for (StateInterval i : intervals) { // appended in time order
      long end = i.toMs() == null ? Long.MAX_VALUE : i.toMs();
      if (!i.machineId().equals(machineId) || end <= fromMs || i.fromMs() >= toMs) continue;
      out.add(new StateInterval(machineId, i.status(), Math.max(fromMs, i.fromMs()), Math.min(toMs, end)));
    }
    return out;
  }

  /** The browser runtime has no maintenance plan yet; the server reads maintenance_window. */
  @Override
  public List<MaintenanceWindow> maintenanceWindows(String machineId) {
    return List.of();
  }
}
