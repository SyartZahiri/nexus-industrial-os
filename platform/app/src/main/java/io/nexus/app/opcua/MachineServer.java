package io.nexus.app.opcua;

import io.nexus.contracts.DomainException;
import io.nexus.plant.machines.FeedbackOutbox;
import io.nexus.plant.machines.MachineWire;
import io.nexus.plant.machines.PlantMachines;
import io.nexus.sim.Simulation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.milo.opcua.sdk.server.EndpointConfig;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.identity.AnonymousIdentityValidator;
import org.eclipse.milo.opcua.sdk.server.identity.CompositeValidator;
import org.eclipse.milo.opcua.sdk.server.identity.UsernameIdentityValidator;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.UserTokenType;
import org.eclipse.milo.opcua.stack.core.types.structured.UserTokenPolicy;
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransport;
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransportConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The simulated machines as an OPC UA server (Spec 9). Anyone on this computer may read and subscribe; only the
 * gateway, signed in with a password made fresh at every start, may call methods, because a command past
 * SmartFactory would put the two out of step. Bound to 127.0.0.1 with SecurityPolicy None: the platform has no
 * login and is for local use (Spec 16); the password travels unencrypted over the loopback interface only.
 *
 * <p>Methods run under this server's own lock, never under the platform lock: the gateway waits for the answer
 * while it holds the platform lock, so taking it here would deadlock. The answers of the last calls are kept by call
 * id; a call sent again after its answer was lost runs only once.
 */
public final class MachineServer {
  public static final String NAMESPACE = "urn:nexus:machines";
  static final String GATEWAY_USER = "nexus-gateway";
  private static final Logger log = LoggerFactory.getLogger(MachineServer.class);
  private static final int KEPT_ANSWERS = 16;

  private final int port;
  private final String password = newPassword();
  private final Object lock = new Object();
  private final FeedbackOutbox outbox = new FeedbackOutbox();
  private final Map<Long, Variant[]> answers = new LinkedHashMap<>();   // oldest first, guarded by lock
  private Simulation simulation;                                       // guarded by lock
  private MachineNamespace namespace;                                  // guarded by lock
  private OpcUaServer server;
  private volatile boolean loseNextAnswer;

  public MachineServer(int port) {
    this.port = port == 0 ? freePort() : port;
  }

  public synchronized MachineServer start() {
    if (server != null) return this;
    var anonymous = new UserTokenPolicy("anonymous", UserTokenType.Anonymous, null, null, null);
    var gateway = new UserTokenPolicy("gateway", UserTokenType.UserName, null, null, SecurityPolicy.None.getUri());
    EndpointConfig endpoint = EndpointConfig.newBuilder()
        .setBindAddress("127.0.0.1").setBindPort(port).setHostname("127.0.0.1").setPath("/nexus")
        .setSecurityPolicy(SecurityPolicy.None).setSecurityMode(MessageSecurityMode.None)
        .addTokenPolicies(anonymous, gateway).build();
    OpcUaServerConfig config = OpcUaServerConfig.builder()
        .setApplicationUri("urn:nexus:machines").setProductUri("urn:nexus")
        .setApplicationName(LocalizedText.english("NEXUS Maschinen"))
        .setEndpoints(Set.of(endpoint))
        .setIdentityValidator(new CompositeValidator(AnonymousIdentityValidator.INSTANCE,
            new UsernameIdentityValidator(c -> GATEWAY_USER.equals(c.getUsername()) && password.equals(c.getPassword()))))
        .build();
    var opcUa = new OpcUaServer(config, profile -> new OpcTcpServerTransport(OpcTcpServerTransportConfig.newBuilder().build()));
    var nodes = new MachineNamespace(opcUa, this);
    nodes.startup();
    try {
      opcUa.startup().get(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("OPC-UA-Server: Start unterbrochen", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("OPC-UA-Server startet nicht auf Port " + port, e);
    }
    synchronized (lock) {
      namespace = nodes;
      if (simulation != null) namespace.publish(simulation);     // after a restart the values are there at once
    }
    server = opcUa;
    log.info("OPC-UA-Server der Maschinen unter {}", endpointUrl());
    return this;
  }

  public synchronized void stop() {
    if (server == null) return;
    try {
      server.shutdown().get(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException | TimeoutException e) {
      log.warn("OPC-UA-Server hält nicht sauber an", e);
    }
    server = null;
  }

  public String endpointUrl() {
    return "opc.tcp://127.0.0.1:" + port + "/nexus";
  }

  String gatewayPassword() {
    return password;
  }

  /** The running machines; only inside execute. */
  Simulation sim() {
    if (simulation == null) throw DomainException.conflict("Maschinen noch nicht gestartet");
    return simulation;
  }

  /** New machines; only inside execute. */
  void newMachines(long seed) {
    simulation = PlantMachines.newSimulation(seed, outbox);
  }

  /** Runs one command at most once per call id; answers [reports, error or null]. */
  Variant[] execute(long callId, Runnable command) throws UaException {
    Variant[] answer;
    synchronized (lock) {
      answer = answers.get(callId);
      if (answer == null) {
        String error = null;
        try {
          command.run();
        } catch (RuntimeException e) {
          error = MachineWire.error(e);
        }
        answer = new Variant[] {Variant.ofString(MachineWire.items(outbox.drain())), Variant.ofString(error)};
        answers.put(callId, answer);
        if (answers.size() > KEPT_ANSWERS) answers.remove(answers.keySet().iterator().next());
        if (simulation != null) namespace.publish(simulation);
      }
    }
    if (loseNextAnswer) {
      loseNextAnswer = false;
      throw new UaException(StatusCodes.Bad_Timeout);
    }
    return answer;
  }

  /** Tests only: the next call runs, but its answer is lost as if the connection broke. */
  void loseNextAnswer() {
    loseNextAnswer = true;
  }

  /** Tests only: a value as a broken device would deliver it; the next call overwrites it. */
  void override(String path, DataValue value) {
    synchronized (lock) {
      namespace.override(path, value);
    }
  }

  private static int freePort() {
    try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String newPassword() {
    byte[] bytes = new byte[24];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
