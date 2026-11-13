package io.nexus.app.flowpilot;

import io.nexus.contracts.state.TransportStatus;
import io.nexus.flowpilot.FlowPilot;
import io.nexus.flowpilot.master.FlowPilotMasterData;
import io.nexus.flowpilot.store.Reservation;
import io.nexus.flowpilot.store.StockLine;
import io.nexus.flowpilot.store.StockStore;
import io.nexus.flowpilot.store.TransportOrder;
import io.nexus.flowpilot.store.TransportStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** FlowPilot's REST API: stock, reservations, transports, AGVs and the path graph. Commands go through the facade. */
@RestController
@RequestMapping("/api/v1")
class FlowPilotController {
  record LocationStock(String locationId, int onHand, int reserved, int available) {}

  record MaterialView(String id, String name, String kind, int reorderPoint, int reorderQty,
                      List<LocationStock> stock) {}

  record Receipt(@NotBlank String materialId, @NotBlank String locationId, @Min(1) int quantity) {}

  record FaultRequest(String faultTypeId) {}

  record EdgeView(String id, String from, String to, double length, boolean blocked) {}

  record Layout(List<FlowPilotMasterData.Node> nodes, List<EdgeView> edges,
                List<FlowPilotMasterData.StorageLocation> locations, List<FlowPilotMasterData.MachineDock> machineDocks,
                List<FlowPilotMasterData.ConveyorDock> conveyorDocks, List<FlowPilotMasterData.Charger> chargers) {}

  private final FlowPilot flowPilot;
  private final StockStore stock;
  private final TransportStore transports;

  FlowPilotController(FlowPilot flowPilot, StockStore stock, TransportStore transports) {
    this.flowPilot = flowPilot;
    this.stock = stock;
    this.transports = transports;
  }

  /** Master data of every material with its stock per storage location. */
  @GetMapping("/materials")
  List<MaterialView> materials() {
    List<StockLine> lines = stock.lines();
    List<MaterialView> out = new ArrayList<>();
    for (var m : FlowPilotMasterData.materials()) {
      List<LocationStock> at = new ArrayList<>();
      for (StockLine l : lines) {
        if (l.materialId().equals(m.id())) at.add(new LocationStock(l.locationId(), l.onHand(), l.reserved(), l.available()));
      }
      out.add(new MaterialView(m.id(), m.name(), m.kind(), m.reorderPoint(), m.reorderQty(), at));
    }
    return out;
  }

  @GetMapping("/stock")
  List<StockLine> stock() {
    return stock.lines();
  }

  @GetMapping("/reservations")
  List<Reservation> reservations(@RequestParam String orderId) {
    return stock.reservationsOfOrder(orderId);
  }

  /** Goods receipt. 400 for a quantity below 1, 404 for an unknown material or location, 409 if it does not fit. */
  @PostMapping("/stock/receipts")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void receive(@Valid @RequestBody Receipt r) {
    flowPilot.receive(r.materialId(), r.locationId(), r.quantity());
  }

  @GetMapping("/transports")
  List<TransportOrder> transports(@RequestParam(required = false) TransportStatus status) {
    List<TransportOrder> all = transports.all();
    if (status == null) return all;
    return all.stream().filter(t -> t.status() == status).toList();
  }

  @GetMapping("/agvs")
  List<FlowPilot.VehicleView> agvs() {
    return flowPilot.vehicles();
  }

  @PostMapping("/agvs/{id}/enable")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void enable(@PathVariable String id) {
    flowPilot.setAgvEnabled(id, true);
  }

  @PostMapping("/agvs/{id}/disable")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void disable(@PathVariable String id) {
    flowPilot.setAgvEnabled(id, false);
  }

  @PostMapping("/agvs/{id}/charge")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void charge(@PathVariable String id) {
    flowPilot.sendToCharge(id);
  }

  /** Body optional; without a fault type NAV is used. */
  @PostMapping("/agvs/{id}/fault")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void fault(@PathVariable String id, @RequestBody(required = false) FaultRequest r) {
    flowPilot.injectFault(id, r == null ? null : r.faultTypeId());
  }

  @PostMapping("/routes/edges/{id}/block")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void block(@PathVariable String id) {
    flowPilot.setEdgeBlocked(id, true);
  }

  @PostMapping("/routes/edges/{id}/unblock")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void unblock(@PathVariable String id) {
    flowPilot.setEdgeBlocked(id, false);
  }

  /** Path graph with block state, docks and chargers, for the 3D view. */
  @GetMapping("/layout")
  Layout layout() {
    List<EdgeView> edges = new ArrayList<>();
    for (var e : FlowPilotMasterData.edges()) {
      edges.add(new EdgeView(e.id(), e.from(), e.to(), e.length(), flowPilot.graph().isBlocked(e.id())));
    }
    return new Layout(FlowPilotMasterData.nodes(), edges, FlowPilotMasterData.locations(),
        FlowPilotMasterData.machineDocks(), FlowPilotMasterData.conveyorDocks(), FlowPilotMasterData.chargers());
  }
}
