package io.nexus.app.opcua;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventIds;
import io.nexus.contracts.memory.MemoryEventLog;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.port.MachineFeedback;
import io.nexus.contracts.sim.SimTime;
import io.nexus.contracts.state.MachineStatus;
import io.nexus.plant.machines.PlantMachines;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The gateway on its own: a real machine server, a memory event bus and a feedback target the test controls. */
class OpcUaMachinesTest {
  /** Security review: one announcement SmartFactory cannot take must not leave the other machines dispatchable. */
  @Test
  void everyMachineIsAnnouncedUnknownEvenIfOneAnnouncementFails() throws Exception {
    var server = new MachineServer(0).start();
    var work = new MemoryUnitOfWork();
    var eventIds = new EventIds(1);
    var bus = new EventBus(new MemoryEventLog(work), work, new SimTime(), () -> eventIds);
    List<String> unknown = new ArrayList<>();
    MachineFeedback target = new MachineFeedback() {
      @Override public void statusChanged(String machineId, MachineStatus from, MachineStatus to) {
        if (to != MachineStatus.UNKNOWN) return;
        unknown.add(machineId);
        if (unknown.size() == 1) throw new IllegalStateException("Empfänger kaputt");
      }

      @Override public void faultRaised(String machineId, String faultTypeId, String label, boolean scrapped) {}

      @Override public void jobFinished(String machineId, String orderId, int stepSeq, int good, int scrap) {}

      @Override public void conveyorDelivered(String machineId, String orderId, int stepSeq, int quantity) {}
    };
    var gateway = OpcUaMachines.connect(server, target, bus);
    try {
      synchronized (bus) {
        gateway.reset(1);
      }
      server.stop();
      long deadline = System.currentTimeMillis() + 30_000;
      while (unknown.size() < PlantMachines.machineIds().size()) {
        assertThat(System.currentTimeMillis()).as("waited 30 s, announced: " + unknown).isLessThan(deadline);
        try {
          synchronized (bus) {
            gateway.step(100);
          }
        } catch (IllegalStateException expected) {
          // the broken announcement is reported to the step, after all others went out
        }
        Thread.sleep(50);
      }
      assertThat(unknown).containsExactlyElementsOf(PlantMachines.machineIds());
    } finally {
      gateway.close();
      server.stop();
    }
  }
}
