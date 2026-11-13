package io.nexus.flowpilot.store.memory;

import io.nexus.contracts.DomainException;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.state.AgvStatus;
import io.nexus.flowpilot.master.FlowPilotMasterData;
import io.nexus.flowpilot.store.AgvRecord;
import io.nexus.flowpilot.store.FleetStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** FleetStore in memory, seeded with six idle AGVs at their chargers. */
public final class MemoryFleetStore implements FleetStore {
  private final MemoryUnitOfWork work;
  private final TreeMap<String, AgvRecord> agvs = new TreeMap<>();
  private final TreeSet<String> blocked = new TreeSet<>();
  private final Set<String> edgeIds = new HashSet<>(); // lookups only

  public MemoryFleetStore(MemoryUnitOfWork work) {
    this.work = work;
    for (var a : FlowPilotMasterData.agvs()) {
      agvs.put(a.id(), new AgvRecord(a.id(), a.name(), AgvStatus.IDLE, a.nodeId(), 100.0, true, 0));
    }
    for (var e : FlowPilotMasterData.edges()) edgeIds.add(e.id());
  }

  @Override
  public List<AgvRecord> agvs() {
    return new ArrayList<>(agvs.values());
  }

  @Override
  public AgvRecord save(AgvRecord agv) {
    AgvRecord before = agvs.get(agv.id());
    if (before == null) throw DomainException.notFound("AGV", agv.id());
    if (before.version() != agv.version()) {
      throw DomainException.conflict("AGV " + agv.id() + " wurde zwischenzeitlich geändert");
    }
    var saved = new AgvRecord(agv.id(), agv.name(), agv.status(), agv.nodeId(), agv.battery(), agv.enabled(),
        agv.version() + 1);
    agvs.put(agv.id(), saved);
    work.onRollback(() -> agvs.put(agv.id(), before));
    return saved;
  }

  @Override
  public Set<String> blockedEdges() {
    return Collections.unmodifiableSet(new TreeSet<>(blocked));
  }

  @Override
  public void setEdgeBlocked(String edgeId, boolean isBlocked) {
    if (!edgeIds.contains(edgeId)) throw DomainException.notFound("Kante", edgeId);
    boolean changed = isBlocked ? blocked.add(edgeId) : blocked.remove(edgeId);
    if (changed) {
      work.onRollback(() -> {
        if (isBlocked) blocked.remove(edgeId);
        else blocked.add(edgeId);
      });
    }
  }
}
