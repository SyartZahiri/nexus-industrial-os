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
import io.nexus.flowpilot.store.StockLine;
import io.nexus.flowpilot.store.StockStore;
import io.nexus.flowpilot.store.TransportOrder;
import io.nexus.flowpilot.store.TransportStore;
import io.nexus.flowpilot.store.memory.MemoryFleetStore;
import io.nexus.flowpilot.store.memory.MemoryStockStore;
import io.nexus.flowpilot.store.memory.MemoryTransportStore;
import io.nexus.flowpilot.transport.TransportServiceImpl;
import java.util.List;

/**
 * Facade of the module. Commands run in units of work of their own.
 * The simulation clock is advanced by the caller (the simulation loop), not by step.
 *
 * <p>Threading: every method holds the monitor of the event publisher (the EventBus), the same lock SmartFactory
 * and the bus itself use. One platform-wide lock means one lock order: a thread delivering events into SmartFactory
 * and a thread calling FlowPilot from SmartFactory can never wait for each other.
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
  private final Object lock;
  private final StockStore stockStore;
  private final TransportStore transportStore;

  public static FlowPilot inMemory(EventPublisher events, MemoryUnitOfWork work, SimTime time, IdSource ids) {
    return new FlowPilot(new MemoryStockStore(work), new MemoryTransportStore(work), new MemoryFleetStore(work),
        events, work, time, ids);
  }

  public FlowPilot(StockStore stock, TransportStore transportStore, FleetStore fleetStore, EventPublisher events,
                   UnitOfWork work, SimTime time, IdSource ids) {
    this.lock = events;
    this.stockStore = stock;
    this.transportStore = transportStore;
    this.time = time;
    this.graph = new PathGraph(FlowPilotMasterData.nodes(), FlowPilotMasterData.edges());
    this.materials = new MaterialServiceImpl(stock, events, ids, work, time);
    this.transports = new TransportServiceImpl(transportStore, stock, ids, events, work, time);
    this.replenishment = new Replenishment(stock, materials, events, work);
    this.fleet = new Fleet(fleetStore, transportStore, materials, graph, events, work, time);
    this.materialPort = new MaterialService() {
      @Override public ReservationResult reserve(ReservationRequest r) {
        synchronized (lock) { return materials.reserve(r); }
      }
      @Override public void release(String requestId) {
        synchronized (lock) { materials.release(requestId); }
      }
      @Override public List<Availability> availability(String materialId) {
        synchronized (lock) { return materials.availability(materialId); }
      }
    };
    this.transportPort = new TransportService() {
      @Override public String requestTransport(TransportRequest r) {
        synchronized (lock) { return transports.requestTransport(r); }
      }
      @Override public void cancel(String transportId) {
        synchronized (lock) {
          fleet.checkCancellable(transportId);
          transports.cancel(transportId);
          fleet.transportChanged(transportId);
        }
      }
      @Override public void redirect(String transportId, Place newTo) {
        synchronized (lock) {
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
  public void step(long dtMs) {
    synchronized (lock) {
      replenishment.step(time.nowMs());
      fleet.step(dtMs);
    }
  }

  /** Goods receipt, e.g. from the REST API. */
  public void receive(String materialId, String locationId, int quantity) {
    synchronized (lock) {
      work.inNewTransaction(() -> materials.receive(materialId, locationId, quantity));
    }
  }

  /**
   * For a reset of the simulation, after the stores were put back to master data: reloads the fleet and drops
   * replenishment deliveries under way.
   */
  public void reset() {
    synchronized (lock) {
      replenishment.reset();
      fleet.reload();
    }
  }

  /** Goods issue, e.g. shipping finished goods until RouteMind collects them. */
  public void issue(String materialId, String locationId, int quantity) {
    synchronized (lock) {
      work.inNewTransaction(() -> materials.issue(materialId, locationId, quantity));
    }
  }

  /** Every stock line, ordered by material and location. */
  public List<StockLine> stock() {
    synchronized (lock) {
      return stockStore.lines();
    }
  }

  /** Transports that are neither delivered nor cancelled, ordered by priority, age and id. */
  public List<TransportOrder> openTransports() {
    synchronized (lock) {
      return transportStore.open();
    }
  }

  public List<VehicleView> vehicles() {
    synchronized (lock) {
      return fleet.views();
    }
  }

  public void setAgvEnabled(String agvId, boolean enabled) {
    synchronized (lock) {
      fleet.setEnabled(agvId, enabled);
    }
  }

  public void sendToCharge(String agvId) {
    synchronized (lock) {
      fleet.sendToCharge(agvId);
    }
  }

  /** @param faultTypeId NAV or COMM; null means NAV */
  public void injectFault(String agvId, String faultTypeId) {
    synchronized (lock) {
      fleet.injectFault(agvId, faultTypeId);
    }
  }

  public void setEdgeBlocked(String edgeId, boolean blocked) {
    synchronized (lock) {
      fleet.setEdgeBlocked(edgeId, blocked);
    }
  }

  public PathGraph graph() {
    return graph;
  }
}
