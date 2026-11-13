package io.nexus.smartfactory.store;

import io.nexus.contracts.state.MachineStatus;
import java.util.List;

/** Storage of machine states and state intervals. Writes join the current unit of work. */
public interface MachineStore {
  /** Ordered by id. */
  List<MachineRecord> machines();

  /** Optimistic like {@link OrderStore#update}. */
  MachineRecord save(MachineRecord m);

  /** Closes the open interval of the machine at nowMs and opens one with the new status. */
  void changeState(String machineId, MachineStatus status, long nowMs);

  /** Intervals overlapping the window, clipped to it (an open interval ends at toMs), ordered by start. */
  List<StateInterval> intervals(String machineId, long fromMs, long toMs);

  List<MaintenanceWindow> maintenanceWindows(String machineId);

  record MaintenanceWindow(String machineId, long startMs, long endMs) {}
}
