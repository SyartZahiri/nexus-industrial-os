package io.nexus.flowpilot;

import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventPublisher;
import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.port.IdSource;
import io.nexus.contracts.port.MaterialService;
import io.nexus.contracts.port.TransportService;
import io.nexus.contracts.sim.SimTime;
import io.nexus.contracts.state.AgvStatus;
import io.nexus.flowpilot.fleet.Fleet;
import io.nexus.flowpilot.master.FlowPilotMasterData;
import io.nexus.flowpilot.material.MaterialServiceImpl;
import io.nexus.flowpilot.material.Replenishment;
import io.nexus.flowpilot.routing.PathGraph;
import io.nexus.flowpilot.store.FleetStore;
import io.nexus.flowpilot.store.StockStore;
import io.nexus.flowpilot.store.TransportStore;
import io.nexus.flowpilot.store.memory.MemoryFleetStore;
import io.nexus.flowpilot.store.memory.MemoryStockStore;
import io.nexus.flowpilot.store.memory.MemoryTransportStore;
import io.nexus.flowpilot.transport.TransportServiceImpl;
import java.util.List;

/**
 * Facade of the module. Every public method is synchronized; commands run in units of work of their own.
 * The simulation clock is advanced by the caller (the simulation loop), not by step.
 */
public final class FlowPilot {
  public record VehicleView(String id, String name, AgvStatus status, double x, double z, double heading,
                            double speed, double battery, String transportId, List<String> route) {}

  private final SimTime time;
  private final UnitOfWork work;
  private final PathGraph graph;
  private final MaterialServiceImpl materials;
  private final TransportServiceImpl transports;
  private final Replenishment replenishment;
  private final Fleet fleet;
  private final MaterialService materialPort;
  private final TransportService transportPort;

  public static FlowPilot inMemory(EventPublisher events, MemoryUnitOfWork work, SimTime time, IdSource ids) {
    return new FlowPilot(new MemoryStockStore(work), new MemoryTransportStore(work), new MemoryFleetStore(work),
        events, work, time, ids);
  }

  public FlowPilot(StockStore stock, TransportStore transportStore, FleetStore fleetStore, EventPublisher events,
                   UnitOfWork work, SimTime time, IdSource ids) {
    this.time = time;
    this.graph = new PathGraph(FlowPilotMasterData.nodes(), FlowPilotMasterData.edges());
    this.materials = new MaterialServiceImpl(stock, events, ids, work, time);
    this.transports = new TransportServiceImpl(transportStore, stock, ids, events, work, time);
    this.replenishment = new Replenishment(stock, materials, events, work);
    this.fleet = new Fleet(fleetStore, transportStore, materials, graph, events, work, time);
    this.materialPort = new MaterialService() {
      @Override public ReservationResult reserve(ReservationRequest r) {
        synchronized (FlowPilot.this) { return materials.reserve(r); }
      }
      @Override public void release(String requestId) {
        synchronized (FlowPilot.this) { materials.release(requestId); }
      }
      @Override public List<Availability> availability(String materialId) {
        synchronized (FlowPilot.this) { return materials.availability(materialId); }
      }
    };
    this.transportPort = new TransportService() {
      @Override public String requestTransport(TransportRequest r) {
        synchronized (FlowPilot.this) { return transports.requestTransport(r); }
      }
      @Override public void cancel(String transportId) {
        synchronized (FlowPilot.this) {
          transports.cancel(transportId);
          fleet.transportChanged(transportId);
        }
      }
      @Override public void redirect(String transportId, Place newTo) {
        synchronized (FlowPilot.this) {
          transports.redirect(transportId, newTo);
          fleet.transportChanged(transportId);
        }
      }
    };
    this.work = work;
  }


  /** MaterialService for other modules; calls are serialised with the simulation step. */
  public MaterialService materials() {
    return materialPort;
  }

  /** TransportService for other modules; cancel and redirect reach the AGV carrying the transport at once. */
  public TransportService transports() {
    return transportPort;
  }

  /** One simulation step: replenishment, dispatch, movement, loading, charging. */
  public synchronized void step(long dtMs) {
    replenishment.step(time.nowMs());
    fleet.step(dtMs);
  }

  /** Goods receipt, e.g. from the REST API. */
  public synchronized void receive(String materialId, String locationId, int quantity) {
    work.inNewTransaction(() -> materials.receive(materialId, locationId, quantity));
  }

  public synchronized List<VehicleView> vehicles() {
    return fleet.views();
  }

  public synchronized void setAgvEnabled(String agvId, boolean enabled) {
    fleet.setEnabled(agvId, enabled);
  }

  public synchronized void sendToCharge(String agvId) {
    fleet.sendToCharge(agvId);
  }

  /** @param faultTypeId NAV or COMM; null means NAV */
  public synchronized void injectFault(String agvId, String faultTypeId) {
    fleet.injectFault(agvId, faultTypeId);
  }

  public synchronized void setEdgeBlocked(String edgeId, boolean blocked) {
    fleet.setEdgeBlocked(edgeId, blocked);
  }

  public PathGraph graph() {
    return graph;
  }
}
