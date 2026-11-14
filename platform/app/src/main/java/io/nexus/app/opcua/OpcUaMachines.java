package io.nexus.app.opcua;

import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.port.MachineFeedback;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.plant.machines.FeedbackOutbox;
import io.nexus.plant.machines.MachineGateway;
import io.nexus.plant.machines.MachineWire;
import io.nexus.plant.machines.PlantMachines;
import io.nexus.sim.SimRules;
import io.nexus.sim.Simulation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.SessionActivityListener;
import org.eclipse.milo.opcua.sdk.client.UaSession;
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.CallMethodRequest;
import org.eclipse.milo.opcua.stack.core.types.structured.CallMethodResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MachineGateway over OPC UA (Spec 2a, 9). Every call of the plant is a method call on the machine server; the
 * reports come back as its answer and reach SmartFactory before the call returns, exactly as in memory. Values for
 * the frame are read in one request when a call has changed them.
 *
 * <p>Called only by the plant under the platform lock, one call at a time. Milo's threads only flip
 * {@link #sessionActive}; nothing here is locked on them. A call whose answer is lost is sent once more with the
 * same call id at once; if that fails too, the machines count as unreachable until the connection is back.
 */
public final class OpcUaMachines implements MachineGateway {
  private static final Logger log = LoggerFactory.getLogger(OpcUaMachines.class);
  /** No answer: the call may or may not have run on the server. */
  private static final Set<Long> NO_ANSWER = Set.of(StatusCodes.Bad_Timeout, StatusCodes.Bad_RequestTimeout,
      StatusCodes.Bad_ConnectionClosed, StatusCodes.Bad_ConnectionRejected, StatusCodes.Bad_SessionClosed,
      StatusCodes.Bad_SessionIdInvalid, StatusCodes.Bad_SecureChannelClosed, StatusCodes.Bad_NotConnected,
      StatusCodes.Bad_ServerNotConnected, StatusCodes.Bad_NoCommunication, StatusCodes.Bad_CommunicationError);
  private static final long MAX_BACKOFF_MS = 30_000;

  private record Pending(String method, Variant[] inputs) {}

  private final OpcUaClient client;
  private final MachineFeedback target;
  private final EventBus bus;
  private final List<String> machineIds = PlantMachines.machineIds();
  private final List<String> conveyorIds = PlantMachines.conveyorIds();
  private final UShort ns;
  private final NodeId plant;
  private final List<NodeId> valueNodes = new ArrayList<>();
  private final Map<String, MachineStatus> known = new TreeMap<>();
  private final Map<String, Simulation.MachineView> views = new TreeMap<>();
  private final Map<String, Simulation.ConveyorView> conveyors = new TreeMap<>();
  private final Set<String> warned = new TreeSet<>();
  private volatile boolean sessionActive = true;
  private boolean reachable = true;
  private boolean suspect;
  private boolean valuesStale = true;
  private long callId;
  private Pending pending;
  private long retryAt;
  private long backoffMs = 1_000;

  public static OpcUaMachines connect(MachineServer server, MachineFeedback target, EventBus bus) {
    try {
      OpcUaClient client = OpcUaClient.create(server.endpointUrl(),
          endpoints -> endpoints.stream()
              .filter(e -> SecurityPolicy.None.getUri().equals(e.getSecurityPolicyUri())).findFirst(),
          transport -> {},
          config -> config.setApplicationUri("urn:nexus:gateway")
              .setApplicationName(LocalizedText.english("NEXUS Maschinen-Gateway"))
              .setIdentityProvider(new UsernameProvider(MachineServer.GATEWAY_USER, server.gatewayPassword()))
              .setRequestTimeout(Unsigned.uint(2_000)));
      client.connect();
      return new OpcUaMachines(client, target, bus);
    } catch (UaException e) {
      throw new IllegalStateException("OPC-UA-Verbindung zu " + server.endpointUrl() + " fehlgeschlagen", e);
    }
  }

  private OpcUaMachines(OpcUaClient client, MachineFeedback target, EventBus bus) {
    this.client = client;
    this.target = target;
    this.bus = bus;
    this.ns = client.getNamespaceTable().getIndex(MachineServer.NAMESPACE);
    if (ns == null) throw new IllegalStateException("OPC-UA-Server ohne Namensraum " + MachineServer.NAMESPACE);
    this.plant = new NodeId(ns, "Plant");
    for (String id : machineIds) {
      known.put(id, MachineStatus.IDLE);
      for (String name : MachineNamespace.MACHINE_VALUES) valueNodes.add(new NodeId(ns, "Machines/" + id + "/" + name));
    }
    for (String id : conveyorIds) {
      for (String name : MachineNamespace.CONVEYOR_VALUES) valueNodes.add(new NodeId(ns, "Conveyors/" + id + "/" + name));
    }
    client.addSessionActivityListener(new SessionActivityListener() {
      @Override public void onSessionActive(UaSession session) { sessionActive = true; }

      @Override public void onSessionInactive(UaSession session) { sessionActive = false; }
    });
  }

  public void close() {
    try {
      client.disconnect();
    } catch (UaException e) {
      log.debug("OPC UA: Trennen nicht bestätigt", e);
    }
  }

  // ---- calls ----------------------------------------------------------------------------------------------

  private void call(String method, Variant... args) {
    if (!reachable || suspect) {
      throw DomainException.conflict("Maschinen nicht erreichbar: OPC-UA-Verbindung getrennt");
    }
    Variant[] inputs = new Variant[args.length + 1];
    inputs[0] = Variant.ofInt64(++callId);
    System.arraycopy(args, 0, inputs, 1, args.length);
    Variant[] answer = send(method, inputs);
    if (answer == null && sessionActive) answer = send(method, inputs);     // same id: runs at most once
    if (answer == null) {
      pending = new Pending(method, inputs);
      suspect = true;                    // the next step treats the machines as unreachable until it is answered
      return;
    }
    deliver(answer);
  }

  /** The answer, or null if none came (the call may or may not have run). */
  private Variant[] send(String method, Variant[] inputs) {
    try {
      CallMethodResult r = client.call(List.of(new CallMethodRequest(plant, new NodeId(ns, "Plant/" + method), inputs)))
          .getResults()[0];
      StatusCode status = r.getStatusCode();
      if (status.isGood()) return r.getOutputArguments();
      if (NO_ANSWER.contains(status.getValue())) return null;
      throw new IllegalStateException("OPC UA lehnt " + method + " ab: " + status);
    } catch (UaException noAnswer) {
      return null;
    }
  }

  /**
   * Hands every report of an answer on in order, even if one of them fails (a lost report would leave SmartFactory
   * and the machines out of step), then throws the machines' error, else the first failed hand-over.
   */
  private void deliver(Variant[] answer) {
    valuesStale = true;
    String reports = answer[0].value() instanceof String s ? s : null;
    MachineWire.Parsed parsed = MachineWire.parseItems(reports, machineIds);
    for (String reason : parsed.rejected()) discard(reason);
    RuntimeException failure = answer[1].value() instanceof String error ? MachineWire.exception(error) : null;
    for (FeedbackOutbox.Item item : parsed.items()) {
      if (item instanceof FeedbackOutbox.StatusChanged s) known.put(s.machineId(), s.to());
      try {
        item.deliverTo(target);
      } catch (RuntimeException notTaken) {
        if (failure == null) failure = notTaken;
        else failure.addSuppressed(notTaken);
      }
    }
    if (failure != null) throw failure;
  }

  private void discard(String reason) {
    log.warn("OPC UA: Meldung verworfen: {}", reason);
    bus.publish(EventTypes.DECISION, "opcua", Severity.WARNING, null,
        new Payloads.Decision("OPC UA", "verworfen", List.of(), "Meldung verworfen: " + reason));
  }

  // ---- MachineGateway ---------------------------------------------------------------------------------------

  @Override
  public void reset(long seed) {
    call("Reset", Variant.ofInt64(seed));
    for (String id : machineIds) known.put(id, MachineStatus.IDLE);
    views.clear();
    conveyors.clear();
  }

  @Override
  public void step(long dtMs) {
    call("Step", Variant.ofInt64(dtMs));
  }

  @Override
  public void setRules(SimRules r) {
    call("SetRules", Variant.ofBoolean(r.randomFaults()), Variant.ofBoolean(r.autoRepair()),
        Variant.ofBoolean(r.autoMaintenance()), Variant.ofDouble(r.maintenanceThreshold()));
  }

  @Override
  public void configure(String machineId, int overridePercent, boolean coolant) {
    call("Configure", Variant.ofString(machineId), Variant.ofInt32(overridePercent), Variant.ofBoolean(coolant));
  }

  @Override public void start(String machineId) { call("Start", Variant.ofString(machineId)); }

  @Override public void stop(String machineId) { call("Stop", Variant.ofString(machineId)); }

  @Override public void repair(String machineId) { call("Repair", Variant.ofString(machineId)); }

  @Override public void maintain(String machineId) { call("Maintain", Variant.ofString(machineId)); }

  @Override
  public void injectFault(String machineId, String faultTypeId) {
    call("InjectFault", Variant.ofString(machineId), Variant.ofString(faultTypeId));
  }

  @Override
  public void startJob(MachineJob job) {
    call("StartJob", Variant.ofString(job.machineId()), Variant.ofString(job.orderId()), Variant.ofInt32(job.stepSeq()),
        Variant.ofString(job.productId()), Variant.ofInt32(job.quantity()), Variant.ofInt64(job.setupMs()),
        Variant.ofInt64(job.processMs()));
  }

  @Override
  public List<Simulation.MachineView> views() {
    refreshIfStale();
    List<Simulation.MachineView> out = new ArrayList<>();
    for (String id : machineIds) {
      Simulation.MachineView v = views.getOrDefault(id,
          new Simulation.MachineView(id, known.get(id), null, 0, 0, 0, 0, 0, 0, 0, null));
      if (known.get(id) == MachineStatus.UNKNOWN) {
        v = new Simulation.MachineView(id, MachineStatus.UNKNOWN, v.orderId(), v.stepSeq(), v.progress(),
            v.temperature(), v.vibration(), v.power(), v.spindle(), v.wear(), v.faultLabel());
      }
      out.add(v);
    }
    return out;
  }

  @Override
  public List<Simulation.ConveyorView> conveyors() {
    refreshIfStale();
    List<Simulation.ConveyorView> out = new ArrayList<>();
    for (String id : conveyorIds) out.add(conveyors.getOrDefault(id, new Simulation.ConveyorView(id, List.of())));
    return out;
  }

  // ---- values -----------------------------------------------------------------------------------------------

  private void refreshIfStale() {
    if (!valuesStale || !reachable || suspect) return;
    try {
      readValues();
    } catch (RuntimeException unanswered) {
      suspect = true;
    }
  }

  /** Reads every machine and conveyor value in one request; bad or implausible values keep the last good ones. */
  private void readValues() {
    List<DataValue> values;
    try {
      values = client.readValues(0, TimestampsToReturn.Neither, valueNodes);
    } catch (UaException e) {
      throw new IllegalStateException("OPC UA: Werte nicht lesbar", e);
    }
    int i = 0;
    for (String id : machineIds) {
      int n = MachineNamespace.MACHINE_VALUES.size();
      Simulation.MachineView view = machineView(id, values.subList(i, i + n));
      i += n;
      keepIfValid(id, view, views);
    }
    for (String id : conveyorIds) {
      int n = MachineNamespace.CONVEYOR_VALUES.size();
      Simulation.ConveyorView view = conveyorView(id, values.subList(i, i + n));
      i += n;
      keepIfValid(id, view, conveyors);
    }
    valuesStale = false;
  }

  private <V> void keepIfValid(String id, V view, Map<String, V> into) {
    if (view != null) {
      into.put(id, view);
      warned.remove(id);
    } else if (warned.add(id)) {
      log.warn("OPC UA: ungültige Werte von {} verworfen, die letzten gültigen bleiben", id);
    }
  }

  private static Simulation.MachineView machineView(String id, List<DataValue> v) {
    for (DataValue d : v) if (!d.statusCode().isGood()) return null;
    MachineStatus status = null;
    if (v.get(0).value().value() instanceof String s) {
      for (MachineStatus candidate : MachineStatus.values()) {
        if (candidate != MachineStatus.UNKNOWN && candidate.name().equals(s)) status = candidate;
      }
    }
    Object order = v.get(1).value().value();
    Object label = v.get(9).value().value();
    if (status == null || (order != null && !(order instanceof String)) || (label != null && !(label instanceof String))
        || !(v.get(2).value().value() instanceof Integer step) || step < 0) {
      return null;
    }
    double progress = number(v.get(3), 0, 1);
    double temperature = number(v.get(4), -20, 200);
    double vibration = number(v.get(5), 0, 50);
    double power = number(v.get(6), 0, 200);
    double spindle = number(v.get(7), 0, 20_000);
    double wear = number(v.get(8), 0, 1);
    if (Double.isNaN(progress + temperature + vibration + power + spindle + wear)) return null;
    return new Simulation.MachineView(id, status, (String) order, step, progress, temperature, vibration, power,
        spindle, wear, (String) label);
  }

  /** The value if it is a number within [min, max], otherwise NaN. */
  private static double number(DataValue d, double min, double max) {
    return d.value().value() instanceof Double x && x >= min && x <= max ? x : Double.NaN;
  }

  private static Simulation.ConveyorView conveyorView(String id, List<DataValue> v) {
    for (DataValue d : v) if (!d.statusCode().isGood()) return null;
    Object[] orders = array(v.get(0));
    Object[] steps = array(v.get(1));
    Object[] positions = array(v.get(2));
    if (orders == null || steps == null || positions == null
        || orders.length != steps.length || orders.length != positions.length) {
      return null;
    }
    List<Simulation.ConveyorItem> items = new ArrayList<>();
    for (int i = 0; i < orders.length; i++) {
      if (!(orders[i] instanceof String order) || !(steps[i] instanceof Integer step)
          || !(positions[i] instanceof Double at) || !(at >= 0 && at <= 1_000)) {
        return null;
      }
      items.add(new Simulation.ConveyorItem(order, step, at));
    }
    return new Simulation.ConveyorView(id, items);
  }

  /** An array value; an empty or missing array is an empty one. */
  private static Object[] array(DataValue d) {
    Object value = d.value().value();
    if (value == null) return new Object[0];
    return value instanceof Object[] a ? a : null;
  }
}
