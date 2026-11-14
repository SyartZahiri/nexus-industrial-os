package io.nexus.plant.machines;

import io.nexus.contracts.DomainException;
import io.nexus.contracts.json.JsonReader;
import io.nexus.contracts.json.JsonWriter;
import io.nexus.contracts.state.IllegalTransitionException;
import io.nexus.contracts.state.MachineStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The text form of machine reports and errors in an OPC UA answer. Only strings, whole numbers and booleans, so
 * both ends see exactly the same values. Reports that make no sense are left out and named (Spec 9: discarded and
 * logged); the caller decides how to log them.
 */
public final class MachineWire {
  public record Parsed(List<FeedbackOutbox.Item> items, List<String> rejected) {}

  private MachineWire() {}

  public static String items(List<FeedbackOutbox.Item> items) {
    var w = new JsonWriter().beginArray();
    for (FeedbackOutbox.Item item : items) {
      w.beginObject();
      switch (item) {
        case FeedbackOutbox.StatusChanged s -> w.name("t").value("status").name("m").value(s.machineId())
            .name("from").value(s.from().name()).name("to").value(s.to().name());
        case FeedbackOutbox.FaultRaised f -> w.name("t").value("fault").name("m").value(f.machineId())
            .name("fault").value(f.faultTypeId()).name("label").value(f.label())
            .name("scrapped").value(f.scrappedWorkpiece());
        case FeedbackOutbox.JobFinished j -> w.name("t").value("finished").name("m").value(j.machineId())
            .name("order").value(j.orderId()).name("step").value(j.stepSeq())
            .name("good").value(j.good()).name("scrap").value(j.scrap());
        case FeedbackOutbox.ConveyorDelivered c -> w.name("t").value("conveyor").name("m").value(c.machineId())
            .name("order").value(c.orderId()).name("step").value(c.stepSeq()).name("qty").value(c.quantity());
      }
      w.endObject();
    }
    return w.endArray().toString();
  }

  public static Parsed parseItems(String json, List<String> machineIds) {
    List<FeedbackOutbox.Item> items = new ArrayList<>();
    List<String> rejected = new ArrayList<>();
    Object root;
    try {
      root = JsonReader.parse(json);
    } catch (RuntimeException unreadable) {
      rejected.add("Meldungen unlesbar: " + unreadable.getMessage());
      return new Parsed(items, rejected);
    }
    if (!(root instanceof List<?> list)) {
      rejected.add("Meldungen sind keine Liste");
      return new Parsed(items, rejected);
    }
    for (Object o : list) {
      try {
        items.add(item(o, machineIds));
      } catch (IllegalArgumentException wrong) {
        rejected.add(wrong.getMessage());
      }
    }
    return new Parsed(items, rejected);
  }

  private static FeedbackOutbox.Item item(Object o, List<String> machineIds) {
    if (!(o instanceof Map<?, ?> m)) throw new IllegalArgumentException("Meldung ist kein Objekt");
    String machine = text(m, "m");
    if (!machineIds.contains(machine)) throw new IllegalArgumentException("unbekannte Maschine " + shorten(machine));
    String type = text(m, "t");
    return switch (type) {
      case "status" -> new FeedbackOutbox.StatusChanged(machine, reported(m, "from"), reported(m, "to"));
      case "fault" -> new FeedbackOutbox.FaultRaised(machine, text(m, "fault"), text(m, "label"), flag(m, "scrapped"));
      case "finished" -> new FeedbackOutbox.JobFinished(machine, text(m, "order"), count(m, "step"), count(m, "good"),
          count(m, "scrap"));
      case "conveyor" -> new FeedbackOutbox.ConveyorDelivered(machine, text(m, "order"), count(m, "step"),
          count(m, "qty"));
      default -> throw new IllegalArgumentException("unbekannte Meldungsart " + shorten(type));
    };
  }

  public static String error(RuntimeException e) {
    var w = new JsonWriter().beginObject();
    if (e instanceof DomainException d) {
      w.name("kind").value("domain").name("domainKind").value(d.kind().name()).name("message").value(d.getMessage());
    } else if (e instanceof IllegalTransitionException t && t.from() != null && t.to() != null) {
      w.name("kind").value("transition").name("entity").value(t.entityId())
          .name("from").value(t.from().name()).name("to").value(t.to().name());
    } else {
      String type = e.getClass().getName();
      w.name("kind").value("other").name("type").value(type.substring(type.lastIndexOf('.') + 1));
    }
    return w.endObject().toString();
  }

  public static RuntimeException exception(String json) {
    try {
      if (!(JsonReader.parse(json) instanceof Map<?, ?> m)) throw new IllegalArgumentException("kein Objekt");
      return switch (text(m, "kind")) {
        case "domain" -> new DomainException(DomainException.Kind.valueOf(text(m, "domainKind")), text(m, "message"));
        case "transition" -> new IllegalTransitionException(text(m, "entity"), MachineStatus.valueOf(text(m, "from")),
            MachineStatus.valueOf(text(m, "to")));
        default -> new IllegalStateException("Maschinenfehler: " + shorten(String.valueOf(m.get("type"))));
      };
    } catch (RuntimeException unreadable) {
      return new IllegalStateException("Fehlermeldung der Maschinen unlesbar");
    }
  }

  private static String text(Map<?, ?> m, String key) {
    if (m.get(key) instanceof String s && !s.isEmpty()) return s;
    throw new IllegalArgumentException("Feld " + key + " fehlt");
  }

  /** A state a machine itself can report: never UNKNOWN, that one only the gateway sets. */
  private static MachineStatus reported(Map<?, ?> m, String key) {
    String s = text(m, key);
    for (MachineStatus status : MachineStatus.values()) {
      if (status != MachineStatus.UNKNOWN && status.name().equals(s)) return status;
    }
    throw new IllegalArgumentException("unbekannter Zustand " + shorten(s));
  }

  private static int count(Map<?, ?> m, String key) {
    if (m.get(key) instanceof Long n && n >= 0 && n <= Integer.MAX_VALUE) return n.intValue();
    throw new IllegalArgumentException("Feld " + key + " ist keine Anzahl");
  }

  private static boolean flag(Map<?, ?> m, String key) {
    if (m.get(key) instanceof Boolean b) return b;
    throw new IllegalArgumentException("Feld " + key + " fehlt");
  }

  private static String shorten(String s) {
    return s.length() <= 40 ? s : s.substring(0, 40) + "…";
  }
}
