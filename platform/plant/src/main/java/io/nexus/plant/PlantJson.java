package io.nexus.plant;

import io.nexus.contracts.Place;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.event.Payload;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.json.JsonWriter;
import io.nexus.flowpilot.FlowPilot.VehicleView;
import io.nexus.sim.Simulation;
import java.util.List;

/**
 * JSON of the plant's frame and events, written by hand: the core has no reflection, and the server sends exactly
 * this text over its WebSocket, so both runtimes give the user interface the same bytes.
 */
public final class PlantJson {
  private PlantJson() {}

  public static String frame(PlantFrame f) {
    var w = new JsonWriter().beginObject();
    w.name("simTimeMs").value(f.simTimeMs());
    w.name("machines").beginArray();
    for (Simulation.MachineView m : f.machines()) {
      w.beginObject().name("id").value(m.id()).name("status").value(m.status().name())
          .name("orderId").value(m.orderId()).name("stepSeq").value(m.stepSeq()).name("progress").value(m.progress())
          .name("temperature").value(m.temperature()).name("vibration").value(m.vibration())
          .name("power").value(m.power()).name("spindle").value(m.spindle()).name("wear").value(m.wear())
          .name("faultLabel").value(m.faultLabel()).endObject();
    }
    w.endArray().name("conveyors").beginArray();
    for (Simulation.ConveyorView c : f.conveyors()) {
      w.beginObject().name("conveyorId").value(c.conveyorId()).name("items").beginArray();
      for (Simulation.ConveyorItem i : c.items()) {
        w.beginObject().name("orderId").value(i.orderId()).name("stepSeq").value(i.stepSeq())
            .name("positionM").value(i.positionM()).endObject();
      }
      w.endArray().endObject();
    }
    w.endArray().name("agvs").beginArray();
    for (VehicleView a : f.agvs()) {
      w.beginObject().name("id").value(a.id()).name("name").value(a.name()).name("status").value(a.status().name())
          .name("x").value(a.x()).name("z").value(a.z()).name("heading").value(a.heading())
          .name("speed").value(a.speed()).name("battery").value(a.battery()).name("transportId").value(a.transportId());
      strings(w.name("route"), a.route());
      w.endObject();
    }
    w.endArray().name("stock").beginArray();
    for (PlantFrame.StockView s : f.stock()) {
      w.beginObject().name("materialId").value(s.materialId()).name("locationId").value(s.locationId())
          .name("onHand").value(s.onHand()).name("reserved").value(s.reserved()).endObject();
    }
    w.endArray().name("orders").beginArray();
    for (PlantFrame.OrderView o : f.orders()) {
      w.beginObject().name("id").value(o.id()).name("productId").value(o.productId()).name("quantity").value(o.quantity())
          .name("priority").value(o.priority()).name("status").value(o.status()).name("dueAtMs").value(o.dueAtMs())
          .name("good").value(o.good()).name("scrapped").value(o.scrapped()).name("stepsDone").value(o.stepsDone())
          .name("steps").value(o.steps()).endObject();
    }
    w.endArray().name("transports").beginArray();
    for (PlantFrame.TransportView t : f.transports()) {
      w.beginObject().name("id").value(t.id()).name("kind").value(t.kind()).name("orderId").value(t.orderId())
          .name("materialId").value(t.materialId()).name("quantity").value(t.quantity()).name("status").value(t.status())
          .name("agvId").value(t.agvId()).name("from").value(t.from()).name("to").value(t.to()).endObject();
    }
    return w.endArray().endObject().toString();
  }

  public static String events(List<NexusEvent> events) {
    var w = new JsonWriter().beginArray();
    for (NexusEvent e : events) event(w, e, true);
    return w.endArray().toString();
  }

  /** One event; without the event id it is the same on every runtime and in every run with the same seed. */
  public static String event(NexusEvent e, boolean withId) {
    var w = new JsonWriter();
    event(w, e, withId);
    return w.toString();
  }

  private static void event(JsonWriter w, NexusEvent e, boolean withId) {
    w.beginObject();
    if (withId) w.name("eventId").value(e.eventId());
    w.name("simTimeMs").value(e.simTimeMs()).name("type").value(e.type()).name("source").value(e.source())
        .name("severity").value(e.severity().name()).name("correlationId").value(e.correlationId())
        .name("payloadType").value(simpleName(e.payload()));
    payload(w.name("payload"), e.payload());
    w.endObject();
  }

  /** Names written into the JSON, not taken from the runtime's class metadata (a build could strip or rename it). */
  private static String simpleName(Payload p) {
    if (p instanceof Payloads.Note) return "Note";
    if (p instanceof Payloads.Decision) return "Decision";
    if (p instanceof Payloads.ConsumerFailed) return "ConsumerFailed";
    if (p instanceof Payloads.OrderCreated) return "OrderCreated";
    if (p instanceof Payloads.OrderStatusChanged) return "OrderStatusChanged";
    if (p instanceof Payloads.OrderRescheduled) return "OrderRescheduled";
    if (p instanceof Payloads.OperationChanged) return "OperationChanged";
    if (p instanceof Payloads.MaterialReserved) return "MaterialReserved";
    if (p instanceof Payloads.MaterialShortage) return "MaterialShortage";
    if (p instanceof Payloads.StockChanged) return "StockChanged";
    if (p instanceof Payloads.TransportCreated) return "TransportCreated";
    if (p instanceof Payloads.TransportStateChanged) return "TransportStateChanged";
    if (p instanceof Payloads.TransportDelivered) return "TransportDelivered";
    if (p instanceof Payloads.AgvStateChanged) return "AgvStateChanged";
    if (p instanceof Payloads.AgvFault) return "AgvFault";
    if (p instanceof Payloads.RouteChanged) return "RouteChanged";
    if (p instanceof Payloads.MachineStatusChanged) return "MachineStatusChanged";
    if (p instanceof Payloads.MachineFault) return "MachineFault";
    if (p instanceof Payloads.MaterialRequested) return "MaterialRequested";
    throw new IllegalStateException("Nutzlast ohne JSON-Form");
  }

  private static void payload(JsonWriter w, Payload p) {
    w.beginObject();
    if (p instanceof Payloads.Note x) {
      w.name("message").value(x.message());
    } else if (p instanceof Payloads.Decision x) {
      w.name("subject").value(x.subject()).name("chosen").value(x.chosen());
      strings(w.name("candidates"), x.candidates());
      w.name("reason").value(x.reason());
    } else if (p instanceof Payloads.ConsumerFailed x) {
      w.name("consumer").value(x.consumer()).name("eventId").value(x.eventId()).name("eventType").value(x.eventType())
          .name("error").value(x.error());
    } else if (p instanceof Payloads.OrderCreated x) {
      w.name("orderId").value(x.orderId()).name("productId").value(x.productId()).name("quantity").value(x.quantity())
          .name("priority").value(name(x.priority())).name("dueAtMs").value(x.dueAtMs());
    } else if (p instanceof Payloads.OrderStatusChanged x) {
      w.name("orderId").value(x.orderId()).name("from").value(name(x.from())).name("to").value(name(x.to()))
          .name("reason").value(x.reason());
    } else if (p instanceof Payloads.OrderRescheduled x) {
      w.name("orderId").value(x.orderId()).name("stepSeq").value(x.stepSeq()).name("fromMachineId").value(x.fromMachineId())
          .name("toMachineId").value(x.toMachineId()).name("reason").value(x.reason());
    } else if (p instanceof Payloads.OperationChanged x) {
      w.name("orderId").value(x.orderId()).name("stepSeq").value(x.stepSeq()).name("machineId").value(x.machineId());
    } else if (p instanceof Payloads.MaterialReserved x) {
      w.name("orderId").value(x.orderId()).name("requestId").value(x.requestId());
      strings(w.name("reservationIds"), x.reservationIds());
    } else if (p instanceof Payloads.MaterialShortage x) {
      w.name("orderId").value(x.orderId()).name("materialId").value(x.materialId()).name("requested").value(x.requested())
          .name("available").value(x.available());
    } else if (p instanceof Payloads.StockChanged x) {
      w.name("materialId").value(x.materialId()).name("locationId").value(x.locationId()).name("onHand").value(x.onHand())
          .name("reserved").value(x.reserved());
    } else if (p instanceof Payloads.TransportCreated x) {
      w.name("transportId").value(x.transportId()).name("kind").value(name(x.kind())).name("orderId").value(x.orderId())
          .name("materialId").value(x.materialId()).name("quantity").value(x.quantity());
      place(w.name("from"), x.from());
      place(w.name("to"), x.to());
    } else if (p instanceof Payloads.TransportStateChanged x) {
      w.name("transportId").value(x.transportId()).name("from").value(name(x.from())).name("to").value(name(x.to()))
          .name("agvId").value(x.agvId());
    } else if (p instanceof Payloads.TransportDelivered x) {
      w.name("transportId").value(x.transportId()).name("kind").value(name(x.kind())).name("orderId").value(x.orderId())
          .name("materialId").value(x.materialId()).name("quantity").value(x.quantity());
      place(w.name("to"), x.to());
    } else if (p instanceof Payloads.AgvStateChanged x) {
      w.name("agvId").value(x.agvId()).name("from").value(name(x.from())).name("to").value(name(x.to()));
    } else if (p instanceof Payloads.AgvFault x) {
      w.name("agvId").value(x.agvId()).name("faultTypeId").value(x.faultTypeId()).name("label").value(x.label());
    } else if (p instanceof Payloads.RouteChanged x) {
      w.name("agvId").value(x.agvId()).name("edgeId").value(x.edgeId());
      strings(w.name("route"), x.route());
    } else if (p instanceof Payloads.MachineStatusChanged x) {
      w.name("machineId").value(x.machineId()).name("from").value(name(x.from())).name("to").value(name(x.to()));
    } else if (p instanceof Payloads.MachineFault x) {
      w.name("machineId").value(x.machineId()).name("faultTypeId").value(x.faultTypeId()).name("label").value(x.label())
          .name("scrappedWorkpiece").value(x.scrappedWorkpiece());
    } else if (p instanceof Payloads.MaterialRequested x) {
      w.name("machineId").value(x.machineId()).name("orderId").value(x.orderId()).name("materialId").value(x.materialId())
          .name("quantity").value(x.quantity());
    } else {
      throw new IllegalStateException("Nutzlast ohne JSON-Form");
    }
    w.endObject();
  }

  private static void place(JsonWriter w, Place p) {
    if (p == null) {
      w.nullValue();
      return;
    }
    w.beginObject().name("kind").value(p.kind().name()).name("id").value(p.id()).endObject();
  }

  private static void strings(JsonWriter w, List<String> values) {
    if (values == null) {
      w.nullValue();
      return;
    }
    w.beginArray();
    for (String v : values) w.value(v);
    w.endArray();
  }

  private static String name(Enum<?> e) {
    return e == null ? null : e.name();
  }
}
