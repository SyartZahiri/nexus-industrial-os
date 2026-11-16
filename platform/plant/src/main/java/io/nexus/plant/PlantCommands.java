package io.nexus.plant;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.json.JsonReader;
import io.nexus.contracts.json.JsonWriter;
import io.nexus.contracts.state.IllegalTransitionException;
import io.nexus.sim.SimRules;
import io.nexus.smartfactory.orders.NewOrder;
import io.nexus.smartfactory.orders.OrderChange;
import io.nexus.smartfactory.planning.DispatchRule;
import java.util.Map;

/**
 * Commands from the browser's user interface as JSON, answered as JSON. The server offers the same commands as
 * REST endpoints; both end in the same facades, so validation and errors are identical (Spec 16).
 *
 * <p>Answer: {"ok":true,"result":…} or {"ok":false,"status":400|404|409|500,"message":…}.
 */
public final class PlantCommands {
  private PlantCommands() {}

  public static String apply(Plant plant, String json) {
    try {
      Object parsed = JsonReader.parse(json);
      if (!(parsed instanceof Map<?, ?> raw)) throw DomainException.invalid("Befehl muss ein JSON-Objekt sein");
      @SuppressWarnings("unchecked") Map<String, Object> c = (Map<String, Object>) raw;
      String result = run(plant, text(c, "command"), c);
      return new JsonWriter().beginObject().name("ok").value(true).name("result").raw(result).endObject().toString();
    } catch (DomainException e) {
      int status = switch (e.kind()) {
        case NOT_FOUND -> 404;
        case INVALID -> 400;
        case CONFLICT -> 409;
      };
      return error(status, e.getMessage());
    } catch (IllegalTransitionException e) {
      return error(409, e.getMessage());
    } catch (RuntimeException | StackOverflowError e) {
      // the class tells a developer where to look; the message may hold internals and stays out
      String type = e.getClass().getName();
      return error(500, "Interner Fehler (" + type.substring(type.lastIndexOf('.') + 1) + ")");
    }
  }

  private static String run(Plant plant, String command, Map<String, Object> c) {
    var sf = plant.smartFactory();
    var fp = plant.flowPilot();
    switch (command) {
      case "createOrder" -> {
        String id = sf.createOrder(new NewOrder(text(c, "productId"), whole(c, "quantity"),
            Priority.valueOf(choice(c, "priority", "NORMAL", "HOCH", "EILIG")), minutes(c, "dueInMinutes")));
        return new JsonWriter().beginObject().name("id").value(id).endObject().toString();
      }
      case "changeOrder" -> sf.changeOrder(text(c, "orderId"), new OrderChange(
          c.get("priority") == null ? null : Priority.valueOf(choice(c, "priority", "NORMAL", "HOCH", "EILIG")),
          c.get("quantity") == null ? null : whole(c, "quantity"),
          c.get("dueInMinutes") == null ? null : minutes(c, "dueInMinutes")));
      case "cancelOrder" -> sf.cancelOrder(text(c, "orderId"));
      case "setDispatchRule" -> {
        PlantRules r = plant.rules();
        plant.setRules(new PlantRules(r.machines(), r.autoOrders(), r.autoOrderIntervalMs(), r.maxOpenOrders(),
            r.shipping(), DispatchRule.valueOf(choice(c, "rule", "PRIORITY", "EDD", "FIFO", "SPT"))));
      }
      case "machineStart" -> sf.startMachine(text(c, "machineId"));
      case "machineStop" -> sf.stopMachine(text(c, "machineId"));
      case "machineFault" -> sf.injectFault(text(c, "machineId"), optionalText(c, "faultTypeId"));
      case "machineRepair" -> sf.repairMachine(text(c, "machineId"));
      case "machineMaintain" -> sf.maintainMachine(text(c, "machineId"));
      case "machineConfigure" -> plant.configureMachine(text(c, "machineId"), whole(c, "override"),
          bool(c, "coolant"));
      case "agvEnable" -> fp.setAgvEnabled(text(c, "agvId"), true);
      case "agvDisable" -> fp.setAgvEnabled(text(c, "agvId"), false);
      case "agvCharge" -> fp.sendToCharge(text(c, "agvId"));
      case "agvFault" -> fp.injectFault(text(c, "agvId"), optionalText(c, "faultTypeId"));
      case "edgeBlock" -> fp.setEdgeBlocked(text(c, "edgeId"), true);
      case "edgeUnblock" -> fp.setEdgeBlocked(text(c, "edgeId"), false);
      case "stockReceipt" -> fp.receive(text(c, "materialId"), text(c, "locationId"), whole(c, "quantity"));
      case "vehicleBreakdown" -> plant.routeMind().breakDown(text(c, "vehicleId"));
      case "vehicleRepair" -> plant.routeMind().repair(text(c, "vehicleId"));
      case "vehicleMaintenance" -> plant.routeMind().setMaintenance(text(c, "vehicleId"), bool(c, "on"));
      case "replanDeliveries" -> plant.routeMind().replan();
      case "setRules" -> plant.setRules(rules(plant.rules(), c));
      case "runScenario" -> {
        String runId = plant.startScenario(text(c, "scenario"));
        return new JsonWriter().beginObject().name("runId").value(runId).endObject().toString();
      }
      default -> throw DomainException.invalid("Unbekannter Befehl: " + command);
    }
    return "null";
  }

  /** Missing fields keep their current value. */
  private static PlantRules rules(PlantRules r, Map<String, Object> c) {
    SimRules m = r.machines();
    SimRules machines = new SimRules(
        c.containsKey("randomFaults") ? bool(c, "randomFaults") : m.randomFaults(),
        c.containsKey("autoRepair") ? bool(c, "autoRepair") : m.autoRepair(),
        c.containsKey("autoMaintenance") ? bool(c, "autoMaintenance") : m.autoMaintenance(),
        c.containsKey("maintenanceThreshold") ? decimal(c, "maintenanceThreshold") : m.maintenanceThreshold());
    return new PlantRules(machines,
        c.containsKey("autoOrders") ? bool(c, "autoOrders") : r.autoOrders(),
        c.containsKey("autoOrderIntervalMinutes") ? minutes(c, "autoOrderIntervalMinutes") : r.autoOrderIntervalMs(),
        c.containsKey("maxOpenOrders") ? whole(c, "maxOpenOrders") : r.maxOpenOrders(),
        c.containsKey("shipping") ? bool(c, "shipping") : r.shipping(),
        c.containsKey("dispatchRule")
            ? DispatchRule.valueOf(choice(c, "dispatchRule", "PRIORITY", "EDD", "FIFO", "SPT")) : r.dispatchRule());
  }

  static String error(int status, String message) {
    return new JsonWriter().beginObject().name("ok").value(false).name("status").value(status)
        .name("message").value(message).endObject().toString();
  }

  static String text(Map<String, Object> c, String field) {
    if (!(c.get(field) instanceof String s) || s.isBlank()) throw DomainException.invalid("Feld " + field + " fehlt");
    return s;
  }

  private static String optionalText(Map<String, Object> c, String field) {
    Object v = c.get(field);
    if (v == null) return null;
    if (!(v instanceof String s)) throw DomainException.invalid("Feld " + field + " muss Text sein");
    return s;
  }

  private static long number(Map<String, Object> c, String field) {
    if (!(c.get(field) instanceof Long n)) throw DomainException.invalid("Feld " + field + " muss eine ganze Zahl sein");
    return n;
  }

  /** A whole number that fits an int; anything larger is refused, never cut to another value. */
  static int whole(Map<String, Object> c, String field) {
    long n = number(c, field);
    if (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) throw DomainException.invalid("Feld " + field + " ist zu gross");
    return (int) n;
  }

  /** Minutes as milliseconds, at most one year. */
  private static long minutes(Map<String, Object> c, String field) {
    long n = number(c, field);
    if (n < 0 || n > 525_600) throw DomainException.invalid("Feld " + field + " muss zwischen 0 und 525600 Minuten liegen");
    return n * 60_000;
  }

  private static double decimal(Map<String, Object> c, String field) {
    Object v = c.get(field);
    if (v instanceof Long n) return n;
    if (v instanceof Double d) return d;
    throw DomainException.invalid("Feld " + field + " muss eine Zahl sein");
  }

  private static boolean bool(Map<String, Object> c, String field) {
    if (!(c.get(field) instanceof Boolean b)) throw DomainException.invalid("Feld " + field + " muss true oder false sein");
    return b;
  }

  private static String choice(Map<String, Object> c, String field, String... allowed) {
    String value = text(c, field);
    for (String a : allowed) if (a.equals(value)) return value;
    throw DomainException.invalid("Feld " + field + " hat einen ungültigen Wert: " + value);
  }
}
