package io.nexus.plant;

import io.nexus.contracts.DomainException;
import io.nexus.contracts.json.JsonReader;
import io.nexus.contracts.json.JsonWriter;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.flowpilot.master.FlowPilotMasterData;
import io.nexus.smartfactory.analytics.Kpis;
import io.nexus.smartfactory.master.SmartFactoryMasterData;
import io.nexus.smartfactory.planning.Plan;
import io.nexus.smartfactory.store.Operation;
import io.nexus.smartfactory.store.OperationMaterial;
import io.nexus.smartfactory.store.ProductionOrder;
import java.util.Map;

/**
 * Read-only questions to the plant as JSON, answered like PlantCommands. Server and browser run the same code, so
 * the user interface gets the same answer in both runtimes.
 */
public final class PlantQueries {
  private static final long MINUTE_MS = 60_000;

  private PlantQueries() {}

  public static String apply(Plant plant, String json) {
    try {
      Object parsed = JsonReader.parse(json);
      if (!(parsed instanceof Map<?, ?> raw)) throw DomainException.invalid("Abfrage muss ein JSON-Objekt sein");
      @SuppressWarnings("unchecked") Map<String, Object> q = (Map<String, Object>) raw;
      String result = switch (PlantCommands.text(q, "query")) {
        case "kpis" -> kpis(plant, q);
        case "rules" -> rules(plant.rules());
        case "products" -> products();
        case "schedule" -> schedule(plant);
        case "order" -> order(plant, PlantCommands.text(q, "orderId"));
        case "warehouse" -> warehouse();
        case "network" -> network(plant);
        case "kpiSeries" -> kpiSeries(plant, q);
        case "scenarios" -> plant.scenariosJson();
        default -> throw DomainException.invalid("Unbekannte Abfrage");
      };
      return new JsonWriter().beginObject().name("ok").value(true).name("result").raw(result).endObject().toString();
    } catch (DomainException e) {
      return PlantCommands.error(switch (e.kind()) {
        case NOT_FOUND -> 404;
        case INVALID -> 400;
        case CONFLICT -> 409;
      }, e.getMessage());
    } catch (RuntimeException e) {
      String type = e.getClass().getName();
      return PlantCommands.error(500, "Interner Fehler (" + type.substring(type.lastIndexOf('.') + 1) + ")");
    }
  }

  private static String kpis(Plant plant, Map<String, Object> q) {
    int window = q.containsKey("windowMinutes") ? PlantCommands.whole(q, "windowMinutes") : 60;
    if (window < 1 || window > 1440) throw DomainException.invalid("windowMinutes muss zwischen 1 und 1440 liegen");
    long now = plant.nowMs();
    Kpis k = plant.smartFactory().kpis(Math.max(0, now - window * MINUTE_MS), now);
    var w = new JsonWriter().beginObject().name("fromMs").value(k.fromMs()).name("toMs").value(k.toMs())
        .name("throughputPerHour").value(k.throughputPerHour()).name("avgLeadTimeS").value(k.avgLeadTimeS())
        .name("p90LeadTimeS").value(k.p90LeadTimeS()).name("onTimeRate").value(k.onTimeRate())
        .name("bottleneckMachineId").value(k.bottleneckMachineId()).name("machines").beginObject();
    for (var e : k.machines().entrySet()) {
      Kpis.MachineKpi m = e.getValue();
      w.name(e.getKey()).beginObject().name("utilization").value(m.utilization())
          .name("availability").value(m.availability()).name("performance").value(m.performance())
          .name("quality").value(m.quality()).name("oee").value(m.oee()).name("timeInStatusMs").beginObject();
      for (MachineStatus s : MachineStatus.values()) w.name(s.name()).value((long) m.timeInStatusMs().get(s));
      w.endObject().endObject();
    }
    return w.endObject().endObject().toString();
  }

  private static String rules(PlantRules r) {
    return new JsonWriter().beginObject()
        .name("randomFaults").value(r.machines().randomFaults()).name("autoRepair").value(r.machines().autoRepair())
        .name("autoMaintenance").value(r.machines().autoMaintenance())
        .name("maintenanceThreshold").value(r.machines().maintenanceThreshold())
        .name("autoOrders").value(r.autoOrders())
        .name("autoOrderIntervalMinutes").value(r.autoOrderIntervalMs() / MINUTE_MS)
        .name("maxOpenOrders").value(r.maxOpenOrders()).name("shipping").value(r.shipping())
        .name("dispatchRule").value(r.dispatchRule().name()).endObject().toString();
  }

  private static JsonWriter nullable(JsonWriter w, Long v) {
    return v == null ? w.nullValue() : w.value((long) v);
  }

  private static String products() {
    var w = new JsonWriter().beginArray();
    for (var p : SmartFactoryMasterData.products()) {
      w.beginObject().name("id").value(p.id()).name("name").value(p.name()).name("shortName").value(p.shortName())
          .name("finishedMaterialId").value(p.finishedMaterialId()).name("routing").beginArray();
      for (var s : SmartFactoryMasterData.routing()) {
        if (!s.productId().equals(p.id())) continue;
        w.beginObject().name("seq").value(s.seq()).name("process").value(s.process().name())
            .name("baseTimeS").value(s.baseTimeS()).name("preferredMachineId").value(s.preferredMachineId()).endObject();
      }
      w.endArray().name("bom").beginArray();
      for (var b : SmartFactoryMasterData.bom()) {
        if (!b.productId().equals(p.id())) continue;
        w.beginObject().name("seq").value(b.seq()).name("materialId").value(b.materialId())
            .name("quantity").value(b.quantity()).endObject();
      }
      w.endArray().endObject();
    }
    return w.endArray().toString();
  }

  private static String schedule(Plant plant) {
    var sf = plant.smartFactory();
    Plan p = sf.plan();
    var w = new JsonWriter().beginObject().name("rule").value(sf.dispatchRule().name()).name("ops").beginArray();
    for (Plan.PlannedOp op : p.ops()) {
      w.beginObject().name("orderId").value(op.orderId()).name("seq").value(op.seq()).name("machineId").value(op.machineId())
          .name("startMs").value(op.startMs()).name("endMs").value(op.endMs()).name("setupMs").value(op.setupMs()).endObject();
    }
    w.endArray().name("unplannable").beginArray();
    for (Plan.Unplannable u : p.unplannable()) {
      w.beginObject().name("orderId").value(u.orderId()).name("seq").value(u.seq()).name("reason").value(u.reason()).endObject();
    }
    w.endArray().name("lateOrders").beginArray();
    for (String id : p.lateOrders()) w.value(id);
    w.endArray().name("utilization").beginObject();
    for (var e : p.utilization().entrySet()) w.name(e.getKey()).value((double) e.getValue());
    return w.endObject().name("bottleneckMachineId").value(p.bottleneckMachineId()).endObject().toString();
  }

  private static String order(Plant plant, String orderId) {
    var sf = plant.smartFactory();
    ProductionOrder o = sf.order(orderId); // NOT_FOUND → 404
    var w = new JsonWriter().beginObject().name("order").beginObject().name("id").value(o.id())
        .name("productId").value(o.productId()).name("quantity").value(o.quantity())
        .name("priority").value(o.priority().name()).name("status").value(o.status().name())
        .name("dueAtMs").value(o.dueAtMs()).name("createdAtMs").value(o.createdAtMs()).name("completedAtMs");
    nullable(w, o.completedAtMs()).name("good").value(o.good()).name("scrapped").value(o.scrapped()).endObject()
        .name("operations").beginArray();
    for (Operation op : sf.operations(orderId)) {
      w.beginObject().name("seq").value(op.seq()).name("process").value(op.process().name())
          .name("machineId").value(op.machineId()).name("status").value(op.status().name()).name("plannedStartMs");
      nullable(w, op.plannedStartMs()).name("plannedEndMs");
      nullable(w, op.plannedEndMs()).name("startedAtMs");
      nullable(w, op.startedAtMs()).name("endedAtMs");
      nullable(w, op.endedAtMs()).name("good").value(op.good()).name("scrap").value(op.scrap()).endObject();
    }
    w.endArray().name("materials").beginArray();
    for (OperationMaterial m : sf.materials(orderId)) {
      w.beginObject().name("stepSeq").value(m.stepSeq()).name("kind").value(m.kind().name())
          .name("materialId").value(m.materialId()).name("quantity").value(m.quantity())
          .name("transportId").value(m.transportId()).name("delivered").value(m.delivered()).endObject();
    }
    return w.endArray().endObject().toString();
  }

  private static String warehouse() {
    var w = new JsonWriter().beginObject().name("materials").beginArray();
    for (var m : FlowPilotMasterData.materials()) {
      w.beginObject().name("id").value(m.id()).name("name").value(m.name()).name("kind").value(m.kind())
          .name("reorderPoint").value(m.reorderPoint()).name("reorderQty").value(m.reorderQty()).endObject();
    }
    w.endArray().name("locations").beginArray();
    for (var l : FlowPilotMasterData.locations()) {
      w.beginObject().name("id").value(l.id()).name("name").value(l.name()).name("zone").value(l.zone())
          .name("capacity").value(l.capacity()).endObject();
    }
    return w.endArray().endObject().toString();
  }

  private static String network(Plant plant) {
    var graph = plant.flowPilot().graph();
    var w = new JsonWriter().beginObject().name("nodes").beginArray();
    for (var n : FlowPilotMasterData.nodes()) {
      w.beginObject().name("id").value(n.id()).name("x").value(n.x()).name("z").value(n.z()).name("dock").value(n.dock())
          .endObject();
    }
    w.endArray().name("edges").beginArray();
    for (var e : FlowPilotMasterData.edges()) {
      w.beginObject().name("id").value(e.id()).name("from").value(e.from()).name("to").value(e.to())
          .name("length").value(e.length()).name("blocked").value(graph.isBlocked(e.id())).endObject();
    }
    return w.endArray().endObject().toString();
  }

  /** Oldest first; sections that would end before the run started are left out, the first one starts at 0 at the earliest. */
  private static String kpiSeries(Plant plant, Map<String, Object> q) {
    int bucket = q.containsKey("bucketMinutes") ? PlantCommands.whole(q, "bucketMinutes") : 10;
    int count = q.containsKey("buckets") ? PlantCommands.whole(q, "buckets") : 12;
    if (bucket < 1 || bucket > 240) throw DomainException.invalid("bucketMinutes muss zwischen 1 und 240 liegen");
    if (count < 1 || count > 48) throw DomainException.invalid("buckets muss zwischen 1 und 48 liegen");
    long now = plant.nowMs();
    var w = new JsonWriter().beginArray();
    for (int i = 0; i < count; i++) {
      long to = now - (long) (count - 1 - i) * bucket * MINUTE_MS;
      if (to <= 0) continue;
      long from = Math.max(0, to - bucket * MINUTE_MS);
      Kpis k = plant.smartFactory().kpis(from, to);
      double sum = 0;
      int n = 0;
      for (Kpis.MachineKpi m : k.machines().values()) {
        if (Double.isFinite(m.oee())) {
          sum += m.oee();
          n++;
        }
      }
      w.beginObject().name("fromMs").value(from).name("toMs").value(to)
          .name("throughputPerHour").value(k.throughputPerHour()).name("onTimeRate").value(k.onTimeRate())
          .name("avgLeadTimeS").value(k.avgLeadTimeS()).name("oee");
      if (n == 0) w.nullValue();
      else w.value(sum / n);
      w.endObject();
    }
    return w.endArray().toString();
  }
}
