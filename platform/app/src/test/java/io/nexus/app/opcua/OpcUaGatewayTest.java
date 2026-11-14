package io.nexus.app.opcua;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.nexus.app.plant.PlantReset;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.port.MachineControl;
import io.nexus.contracts.port.MachineControl.MachineJob;
import io.nexus.plant.Plant;
import io.nexus.plant.machines.FeedbackOutbox;
import io.nexus.plant.machines.LocalMachines;
import java.util.List;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class OpcUaGatewayTest {
  @Autowired Plant plant;
  @Autowired PlantReset reset;
  @Autowired EventBus bus;
  @Autowired MachineServer server;
  @Autowired MachineControl control;

  @Test
  void thePlantDrivesItsMachinesThroughOpcUa() {
    assertThat(plant.machines()).isInstanceOf(OpcUaMachines.class);
    assertThat(control).isSameAs(plant.machines());
  }

  @Test
  void aRefusedJobArrivesAsTheSameExceptionAsInMemory() {
    reset.reset(9);
    var job = new MachineJob("CNC-01", "PO-X", 1, "GH200", 1, 0, 100);
    var local = new LocalMachines(new FeedbackOutbox());
    local.reset(9);
    local.startJob(job);
    Throwable inMemory = catchThrowable(() -> local.startJob(job));
    synchronized (bus) {
      plant.machines().startJob(job);
      assertThatThrownBy(() -> plant.machines().startJob(job))
          .isInstanceOf(inMemory.getClass()).hasMessage(inMemory.getMessage());
    }
    reset.reset(9);                                    // SmartFactory saw a job it never handed out
  }

  @Test
  void visitorsSeeTheValuesThePlantSees() throws Exception {
    reset.reset(9);
    plant.advance(60_000);
    String status;
    synchronized (bus) {
      status = plant.machines().views().getFirst().status().name();      // CNC-01
    }
    OpcUaClient visitor = OpcUaClient.create(server.endpointUrl(), endpoints -> endpoints.stream().findFirst(),
        transport -> {}, config -> config.setApplicationUri("urn:nexus:test"));
    visitor.connect();
    try {
      var ns = visitor.getNamespaceTable().getIndex(MachineServer.NAMESPACE);
      var value = visitor.readValues(0, TimestampsToReturn.Neither,
          List.of(new NodeId(ns, "Machines/CNC-01/Status"))).getFirst();
      assertThat(value.value().value()).isEqualTo(status);
    } finally {
      visitor.disconnect();
    }
  }
}
