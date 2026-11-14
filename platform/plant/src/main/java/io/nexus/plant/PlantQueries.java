package io.nexus.plant;

import io.nexus.contracts.DomainException;
import io.nexus.contracts.json.JsonReader;
import io.nexus.contracts.json.JsonWriter;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.smartfactory.analytics.Kpis;
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
}
