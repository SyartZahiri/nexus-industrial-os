package io.nexus.app.opcua;

import static io.nexus.contracts.state.MachineStatus.IDLE;
import static io.nexus.contracts.state.MachineStatus.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.DomainException;
import io.nexus.contracts.state.IllegalTransitionException;
import io.nexus.plant.machines.FeedbackOutbox;
import io.nexus.plant.machines.MachineWire;
import io.nexus.plant.machines.PlantMachines;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.identity.AnonymousProvider;
import org.eclipse.milo.opcua.sdk.client.identity.IdentityProvider;
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.CallMethodRequest;
import org.eclipse.milo.opcua.stack.core.types.structured.CallMethodResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MachineServerTest {
  private static final AtomicLong CALL_IDS = new AtomicLong();
  private static MachineServer server;
  private static OpcUaClient gateway;
  private static OpcUaClient visitor;
  private static UShort ns;

  static OpcUaClient client(IdentityProvider identity) throws UaException {
    OpcUaClient c = OpcUaClient.create(server.endpointUrl(),
        endpoints -> endpoints.stream()
            .filter(e -> SecurityPolicy.None.getUri().equals(e.getSecurityPolicyUri())).findFirst(),
        transport -> {},
        config -> config.setApplicationUri("urn:nexus:test").setApplicationName(LocalizedText.english("Test"))
            .setIdentityProvider(identity));
    c.connect();
    return c;
  }

  @BeforeAll
  static void start() throws UaException {
    server = new MachineServer(0).start();
    gateway = client(new UsernameProvider(MachineServer.GATEWAY_USER, server.gatewayPassword()));
    visitor = client(new AnonymousProvider());
    ns = gateway.getNamespaceTable().getIndex(MachineServer.NAMESPACE);
  }

  @AfterAll
  static void stop() throws UaException {
    gateway.disconnect();
    visitor.disconnect();
    server.stop();
  }

  static CallMethodResult call(OpcUaClient client, long callId, String method, Variant... args) throws UaException {
    Variant[] in = new Variant[args.length + 1];
    in[0] = Variant.ofInt64(callId);
    System.arraycopy(args, 0, in, 1, args.length);
    return client.call(List.of(new CallMethodRequest(new NodeId(ns, "Plant"), new NodeId(ns, "Plant/" + method), in)))
        .getResults()[0];
  }

  static CallMethodResult call(String method, Variant... args) throws UaException {
    return call(gateway, CALL_IDS.incrementAndGet(), method, args);
  }

  static List<FeedbackOutbox.Item> reports(CallMethodResult r) {
    assertThat(r.getStatusCode().isGood()).as(r.getStatusCode().toString()).isTrue();
    var parsed = MachineWire.parseItems((String) r.getOutputArguments()[0].value(), PlantMachines.machineIds());
    assertThat(parsed.rejected()).isEmpty();
    return parsed.items();
  }

  static RuntimeException error(CallMethodResult r) {
    return MachineWire.exception((String) r.getOutputArguments()[1].value());
  }

  static Object read(String path) throws UaException {
    DataValue v = visitor.readValues(0, TimestampsToReturn.Neither, List.of(new NodeId(ns, path))).getFirst();
    assertThat(v.statusCode().isGood()).as(path + " " + v.statusCode()).isTrue();
    return v.value().value();
  }

  static Variant[] job(String orderId) {
    return new Variant[] {Variant.ofString("CNC-01"), Variant.ofString(orderId), Variant.ofInt32(1),
        Variant.ofString("GH200"), Variant.ofInt32(1), Variant.ofInt64(0L), Variant.ofInt64(100L)};
  }

  @BeforeEach
  void newMachines() throws UaException {
    reports(call("Reset", Variant.ofInt64(7L)));
    reports(call("SetRules", Variant.ofBoolean(false), Variant.ofBoolean(false), Variant.ofBoolean(false),
        Variant.ofDouble(0.85)));
  }

  @Test
  void visitorsReadTheMachinesButMayNotCommandThem() throws UaException {
    assertThat(read("Machines/CNC-01/Status")).isEqualTo("IDLE");
    var refused = call(visitor, CALL_IDS.incrementAndGet(), "InjectFault", Variant.ofString("CNC-01"),
        Variant.ofString(null));
    assertThat(refused.getStatusCode().getValue()).isEqualTo(StatusCodes.Bad_UserAccessDenied);
    assertThat(read("Machines/CNC-01/Status")).isEqualTo("IDLE");
  }

  @Test
  void aCallAnswersWithTheReportsItCaused() throws UaException {
    assertThat(reports(call("StartJob", job("PO-1"))))
        .containsExactly(new FeedbackOutbox.StatusChanged("CNC-01", IDLE, RUNNING));
    assertThat(read("Machines/CNC-01/OrderId")).isEqualTo("PO-1");
    assertThat(reports(call("Step", Variant.ofInt64(100L)))).containsExactly(
        new FeedbackOutbox.StatusChanged("CNC-01", RUNNING, IDLE),
        new FeedbackOutbox.JobFinished("CNC-01", "PO-1", 1, 1, 0));
    assertThat(read("Machines/CNC-01/OrderId")).isNull();
  }

  @Test
  void aRefusedCommandAnswersWithTheSameException() throws UaException {
    reports(call("StartJob", job("PO-1")));
    assertThat(error(call("StartJob", job("PO-2")))).isInstanceOf(IllegalTransitionException.class)
        .hasMessage("Ungültiger Zustandsübergang für CNC-01: RUNNING → RUNNING");
    assertThat(error(call("Configure", Variant.ofString("CNC-01"), Variant.ofInt32(200), Variant.ofBoolean(true))))
        .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.kind()).isEqualTo(DomainException.Kind.INVALID));
  }

  @Test
  void aRepeatedCallIdRunsOnlyOnce() throws UaException {
    long id = CALL_IDS.incrementAndGet();
    var first = call(gateway, id, "StartJob", job("PO-1"));
    var repeated = call(gateway, id, "StartJob", job("PO-1"));
    assertThat(repeated.getOutputArguments()[0].value()).isEqualTo(first.getOutputArguments()[0].value());
    assertThat(repeated.getOutputArguments()[1].value()).as("not run again, so not refused").isNull();
  }

  @Test
  void conveyorValuesAreReadable() throws UaException {
    Object orders = read("Conveyors/CV-03/OrderIds");
    assertThat(orders == null || ((Object[]) orders).length == 0).isTrue();
  }
}
