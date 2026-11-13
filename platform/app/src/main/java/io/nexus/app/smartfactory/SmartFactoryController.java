package io.nexus.app.smartfactory;

import io.nexus.contracts.Domain.MachineKind;
import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.ProcessType;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.sim.SimTime;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.smartfactory.SmartFactory;
import io.nexus.smartfactory.analytics.Kpis;
import io.nexus.smartfactory.master.SmartFactoryMasterData;
import io.nexus.smartfactory.master.SmartFactoryMasterData.BomItem;
import io.nexus.smartfactory.master.SmartFactoryMasterData.RoutingStep;
import io.nexus.smartfactory.orders.NewOrder;
import io.nexus.smartfactory.orders.OrderChange;
import io.nexus.smartfactory.planning.DispatchRule;
import io.nexus.smartfactory.planning.Plan;
import io.nexus.smartfactory.store.MachineRecord;
import io.nexus.smartfactory.store.Operation;
import io.nexus.smartfactory.store.OperationMaterial;
import io.nexus.smartfactory.store.ProductionOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** REST interface of SmartFactory: orders, schedule, products, machines, analytics. */
@RestController
@RequestMapping("/api/v1")
class SmartFactoryController {
  private static final long MINUTE_MS = 60_000;

  record CreateOrder(@NotBlank String productId, @Min(1) @Max(100) int quantity, Priority priority,
                     @NotNull @Min(1) Long dueInMinutes) {}

  record ChangeOrder(Priority priority, @Min(1) @Max(100) Integer quantity, @Min(1) Long dueInMinutes) {}

  record RuleChange(@NotNull DispatchRule rule) {}

  record FaultRequest(String faultTypeId) {}

  record OrderDetail(ProductionOrder order, List<Operation> operations, List<OperationMaterial> materials) {}

  record ScheduleView(DispatchRule rule, List<Plan.PlannedOp> ops, List<Plan.Unplannable> unplannable,
                      List<String> lateOrders, java.util.Map<String, Double> utilization, String bottleneckMachineId) {}

  record ProductView(String id, String name, String shortName, String color, String finishedMaterialId,
                     List<RoutingStep> routing, List<BomItem> billOfMaterial) {}

  record MachineView(String id, String name, MachineKind kind, String model, List<ProcessType> capabilities,
                     double x, double z, MachineStatus status, String lastProductId, String runningOrderId,
                     Integer runningStep) {}

  private final SmartFactory factory;
  private final SimTime time;

  SmartFactoryController(SmartFactory factory, SimTime time) {
    this.factory = factory;
    this.time = time;
  }

  // ---- orders ------------------------------------------------------------------------------------------

  @PostMapping("/orders")
  ResponseEntity<ProductionOrder> create(@Valid @RequestBody CreateOrder in) {
    String id = factory.createOrder(new NewOrder(in.productId(), in.quantity(),
        in.priority() == null ? Priority.NORMAL : in.priority(), in.dueInMinutes() * MINUTE_MS));
    return ResponseEntity.created(URI.create("/api/v1/orders/" + id)).body(factory.order(id));
  }

  @GetMapping("/orders")
  List<ProductionOrder> orders() {
    return factory.orders();
  }

  @GetMapping("/orders/{id}")
  OrderDetail order(@PathVariable String id) {
    return new OrderDetail(factory.order(id), factory.operations(id), factory.materials(id));
  }

  @PatchMapping("/orders/{id}")
  ProductionOrder change(@PathVariable String id, @Valid @RequestBody ChangeOrder in) {
    factory.changeOrder(id, new OrderChange(in.priority(), in.quantity(),
        in.dueInMinutes() == null ? null : in.dueInMinutes() * MINUTE_MS));
    return factory.order(id);
  }

  @PostMapping("/orders/{id}/cancel")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void cancel(@PathVariable String id) {
    factory.cancelOrder(id);
  }

  // ---- schedule ----------------------------------------------------------------------------------------

  @GetMapping("/schedule")
  ScheduleView schedule() {
    Plan p = factory.plan();
    return new ScheduleView(factory.dispatchRule(), p.ops(), p.unplannable(), p.lateOrders(), p.utilization(),
        p.bottleneckMachineId());
  }

  @PutMapping("/schedule/rule")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void rule(@Valid @RequestBody RuleChange in) {
    factory.setDispatchRule(in.rule());
  }

  // ---- master data and machines ------------------------------------------------------------------------

  @GetMapping("/products")
  List<ProductView> products() {
    List<ProductView> out = new ArrayList<>();
    for (var p : SmartFactoryMasterData.products()) {
      out.add(new ProductView(p.id(), p.name(), p.shortName(), p.color(), p.finishedMaterialId(),
          SmartFactoryMasterData.routing().stream().filter(s -> s.productId().equals(p.id())).toList(),
          SmartFactoryMasterData.bom().stream().filter(b -> b.productId().equals(p.id())).toList()));
    }
    return out;
  }

  @GetMapping("/machines")
  List<MachineView> machines() {
    List<MachineView> out = new ArrayList<>();
    for (MachineRecord r : factory.machines()) {
      var m = SmartFactoryMasterData.machine(r.id());
      Operation running = factory.runningOn(r.id());
      out.add(new MachineView(m.id(), m.name(), m.kind(), m.model(), m.capabilities(), m.x(), m.z(), r.status(),
          r.lastProductId(), running == null ? null : running.orderId(), running == null ? null : running.seq()));
    }
    return out;
  }

  @PostMapping("/machines/{id}/start")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void start(@PathVariable String id) {
    factory.startMachine(id);
  }

  @PostMapping("/machines/{id}/stop")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void stop(@PathVariable String id) {
    factory.stopMachine(id);
  }

  @PostMapping("/machines/{id}/repair")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void repair(@PathVariable String id) {
    factory.repairMachine(id);
  }

  @PostMapping("/machines/{id}/maintain")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void maintain(@PathVariable String id) {
    factory.maintainMachine(id);
  }

  @PostMapping("/machines/{id}/fault")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void fault(@PathVariable String id, @RequestBody(required = false) FaultRequest in) {
    factory.injectFault(id, in == null ? null : in.faultTypeId());
  }

  // ---- analytics ---------------------------------------------------------------------------------------

  /** Key figures of the last windowMinutes of simulation time (1 to 1440). */
  @GetMapping("/analytics")
  Kpis analytics(@RequestParam(defaultValue = "60") int windowMinutes) {
    if (windowMinutes < 1 || windowMinutes > 1440) {
      throw DomainException.invalid("windowMinutes muss zwischen 1 und 1440 liegen");
    }
    long now = time.nowMs();
    return factory.kpis(now - windowMinutes * MINUTE_MS, now);
  }
}
