package io.nexus.app.opcua;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.app.event.JdbcEventLog;
import io.nexus.app.event.SimRun;
import io.nexus.app.plant.PlantReset;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.port.MachineControl.MachineJob;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.plant.Plant;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class OpcUaConnectionTest {
  @Autowired Plant plant;
  @Autowired PlantReset reset;
  @Autowired EventBus bus;
  @Autowired MachineServer server;
  @Autowired JdbcEventLog log;
  @Autowired SimRun run;

  @BeforeEach
  void freshPlant() {
    reset.reset(5);
  }

  /** The shared context keeps its server: bring it back and wait until the plant reaches it again. */
  @AfterEach
  void serverBack() {
    server.start();
    stepUntil(() -> plant.smartFactory().machines().stream().noneMatch(m -> m.status() == MachineStatus.UNKNOWN));
    reset.reset(5);
  }

  /** Steps the plant 100 ms at a time until the condition holds; at most 30 s of real time. */
  private void stepUntil(BooleanSupplier condition) {
    long deadline = System.currentTimeMillis() + 30_000;
    while (!condition.getAsBoolean()) {
      assertThat(System.currentTimeMillis()).as("waited 30 s").isLessThan(deadline);
      plant.advance(100);
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }

  private MachineStatus inSmartFactory(String machineId) {
    return plant.smartFactory().machines().stream().filter(m -> m.id().equals(machineId)).findFirst().orElseThrow()
        .status();
  }

  private List<String> connectionNotes() {
    return log.eventsOfRun(run.id()).stream().filter(e -> e.type().equals(EventTypes.OPCUA_CONNECTION))
        .map(e -> ((Payloads.Note) e.payload()).message()).toList();
  }

  @Test
  void machinesAreUnknownWhileTheServerIsGoneAndKnownAgainAfterwards() {
    plant.advance(1_000);
    server.stop();
    stepUntil(() -> inSmartFactory("CNC-01") == MachineStatus.UNKNOWN);
    assertThat(plant.smartFactory().machines()).allSatisfy(m -> assertThat(m.status()).isEqualTo(MachineStatus.UNKNOWN));
    assertThat(plant.frame().machines()).allSatisfy(m -> assertThat(m.status()).isEqualTo(MachineStatus.UNKNOWN));
    assertThat(connectionNotes().getLast()).contains("getrennt");
    synchronized (bus) {
      assertThatThrownBy(() -> plant.machines().start("CNC-01")).isInstanceOfSatisfying(DomainException.class,
          e -> assertThat(e.kind()).isEqualTo(DomainException.Kind.CONFLICT));
    }

    server.start();
    stepUntil(() -> inSmartFactory("CNC-01") != MachineStatus.UNKNOWN);
    assertThat(connectionNotes().getLast()).contains("verbunden");
    synchronized (bus) {
      for (var v : plant.machines().views()) assertThat(inSmartFactory(v.id())).as(v.id()).isEqualTo(v.status());
    }
  }

  @Test
  void stepsDoNotWaitForAServerThatIsGone() {
    server.stop();
    stepUntil(() -> inSmartFactory("CNC-01") == MachineStatus.UNKNOWN);
    long started = System.nanoTime();
    plant.advance(10_000);                                  // 100 steps
    assertThat((System.nanoTime() - started) / 1_000_000).as("ms for 100 steps").isLessThan(2_000);
  }

  @Test
  void aCallWhoseAnswerIsLostRunsExactlyOnce() {
    var job = new MachineJob("CNC-01", "PO-LOST", 1, "GH200", 1, 0, 100);
    synchronized (bus) {
      server.loseNextAnswer();
      plant.machines().startJob(job);
    }
    long started = log.eventsOfRun(run.id()).stream()
        .filter(e -> e.type().equals(EventTypes.MACHINE_STATUS)
            && ((Payloads.MachineStatusChanged) e.payload()).machineId().equals("CNC-01")
            && ((Payloads.MachineStatusChanged) e.payload()).to() == MachineStatus.RUNNING)
        .count();
    assertThat(started).as("the job started once and its report arrived once").isEqualTo(1);
    assertThat(inSmartFactory("CNC-01")).isEqualTo(MachineStatus.RUNNING);
  }

  @Test
  void anImplausibleValueIsDroppedAndTheLastGoodOneKept() {
    plant.advance(1_000);
    double temperature;
    Object cnc02;
    synchronized (bus) {
      var before = plant.machines().views();
      temperature = before.get(0).temperature();
      cnc02 = before.get(1);
    }
    plant.advance(100);                                     // new values on the server; the gateway reads them again
    server.override("Machines/CNC-01/Temperature", new DataValue(Variant.ofDouble(Double.NaN)));
    server.override("Machines/CNC-02/Progress", new DataValue(Variant.ofDouble(0.5), StatusCode.BAD));
    synchronized (bus) {
      var after = plant.machines().views();
      assertThat(after.get(0).temperature()).as("CNC-01 keeps its last good values").isEqualTo(temperature);
      assertThat(after.get(1)).as("CNC-02 keeps its last good values").isEqualTo(cnc02);
    }
  }
}
