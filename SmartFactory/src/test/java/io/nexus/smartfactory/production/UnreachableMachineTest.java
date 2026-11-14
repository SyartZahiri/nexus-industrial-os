package io.nexus.smartfactory.production;

import static io.nexus.contracts.state.MachineStatus.IDLE;
import static io.nexus.contracts.state.MachineStatus.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.port.MachineControl.MachineJob;
import io.nexus.smartfactory.Harness;
import io.nexus.smartfactory.orders.NewOrder;
import io.nexus.smartfactory.testkit.FakeTransportService.FakeTransport;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Spec 9: a machine the gateway cannot reach gets neither work nor material until it is back. */
class UnreachableMachineTest {
  private static final long HOUR = 3_600_000;
  private final Harness h = new Harness();

  private String create(String product, int qty) {
    return h.factory.createOrder(new NewOrder(product, qty, Priority.NORMAL, 8 * HOUR));
  }

  private List<FakeTransport> transportsOf(String orderId) {
    return h.transports.transports().stream().filter(t -> orderId.equals(t.request().orderId())).toList();
  }

  private void deliverAll() {
    for (FakeTransport t : h.transports.open()) h.transports.deliver(t.id());
  }

  @Test
  void anUnreachableMachineIsLeftOutOfThePlanLikeAFailedOne() {
    String id = create("GH200", 4);
    FakeTransport supply = transportsOf(id).getFirst();
    assertThat(supply.to()).isEqualTo(Place.machineIn("CNC-01"));

    h.factory.statusChanged("CNC-01", IDLE, UNKNOWN);

    String alternative = h.factory.operations(id).getFirst().machineId();
    assertThat(alternative).isIn("CNC-02", "CNC-04");
    assertThat(h.transports.transport(supply.id()).to()).isEqualTo(Place.machineIn(alternative));
    assertThat(h.events(EventTypes.MACHINE_STATUS).getLast().severity()).isEqualTo(Severity.WARNING);
  }

  @Test
  void noJobStartsOnAnUnreachableMachineAndWorkResumesWhenItIsBack() {
    h.factory.statusChanged("CNC-02", IDLE, UNKNOWN);
    h.factory.statusChanged("CNC-04", IDLE, UNKNOWN);
    String id = create("GH200", 4);
    h.factory.statusChanged("CNC-01", IDLE, UNKNOWN);
    deliverAll();
    assertThat(h.machines.jobs()).as("no job for a machine nobody can reach").isEmpty();

    h.factory.statusChanged("CNC-01", UNKNOWN, IDLE);
    assertThat(h.machines.jobs()).extracting(MachineJob::machineId).containsExactly("CNC-01");
    assertThat(h.machines.jobs().getFirst().orderId()).isEqualTo(id);
  }
}
