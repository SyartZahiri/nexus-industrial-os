package io.nexus.flowpilot.transport;

import io.nexus.contracts.DomainException;
import io.nexus.contracts.Place;
import io.nexus.flowpilot.master.FlowPilotMasterData;
import java.util.HashMap;
import java.util.Map;

/** Turns a business place into the node of the path graph. Only FlowPilot knows the graph. */
public final class PlaceResolver {
  private final Map<String, FlowPilotMasterData.StorageLocation> locations = new HashMap<>(); // lookups only
  private final Map<String, FlowPilotMasterData.MachineDock> machines = new HashMap<>();
  private final Map<String, String> conveyors = new HashMap<>();
  private final Map<String, String> chargers = new HashMap<>();
  private final Map<String, String> loadingZones = new HashMap<>();

  public PlaceResolver() {
    for (var z : FlowPilotMasterData.loadingZones()) loadingZones.put(z.id(), z.dockNode());
    for (var l : FlowPilotMasterData.locations()) locations.put(l.id(), l);
    for (var m : FlowPilotMasterData.machineDocks()) machines.put(m.machineId(), m);
    for (var c : FlowPilotMasterData.conveyorDocks()) conveyors.put(c.conveyorId(), c.node());
    for (var c : FlowPilotMasterData.chargers()) chargers.put(c.id(), c.nodeId());
  }

  public String node(Place place) {
    if (place == null || place.kind() == null) throw DomainException.invalid("Ort fehlt");
    String id = place.id();
    return switch (place.kind()) {
      case STORAGE -> location(id).dockNode();
      case MACHINE_IN -> machine(id).inNode();
      case MACHINE_OUT -> {
        var m = machine(id);
        if (m.outNode() == null) throw DomainException.invalid(id + " übergibt an ein Band, nicht an ein AGV");
        yield m.outNode();
      }
      case CONVEYOR -> found(conveyors.get(id), "Band", id);
      case CHARGER -> found(chargers.get(id), "Ladestation", id);
      case LOADING -> found(loadingZones.get(id), "Verladezone", id);
    };
  }

  public FlowPilotMasterData.StorageLocation location(String id) {
    var l = id == null ? null : locations.get(id);
    if (l == null) throw DomainException.notFound("Lagerplatz", id);
    return l;
  }

  private FlowPilotMasterData.MachineDock machine(String id) {
    var m = id == null ? null : machines.get(id);
    if (m == null) throw DomainException.notFound("Maschine", id);
    return m;
  }

  private static String found(String node, String what, String id) {
    if (node == null) throw DomainException.notFound(what, id);
    return node;
  }
}
