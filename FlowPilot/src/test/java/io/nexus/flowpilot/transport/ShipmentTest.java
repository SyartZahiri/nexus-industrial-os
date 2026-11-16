package io.nexus.flowpilot.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventIds;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.memory.MemoryEventLog;
import io.nexus.contracts.memory.MemoryIdSource;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.port.MaterialService.Line;
import io.nexus.contracts.port.MaterialService.ReservationRequest;
import io.nexus.contracts.port.MaterialService.ReservationResult;
import io.nexus.contracts.port.TransportService.TransportRequest;
import io.nexus.contracts.sim.SimTime;
import io.nexus.flowpilot.FlowPilot;
import io.nexus.flowpilot.master.FlowPilotMasterData;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Finished goods to the loading zone (Spec 15a, Integration 2 and 3). */
class ShipmentTest {
  private final MemoryUnitOfWork work = new MemoryUnitOfWork();
  private final MemoryEventLog log = new MemoryEventLog(work);
  private final SimTime time = new SimTime();
  private final EventIds ids = new EventIds(1);
  private final EventBus bus = new EventBus(log, work, time, () -> ids);
  private final FlowPilot fp = FlowPilot.inMemory(bus, work, time, new MemoryIdSource(work));

  private ReservationResult.Success reserveFinished(int qty) {
    return (ReservationResult.Success) fp.materials().reserve(
        new ReservationRequest("DL-0001/R", "PO-0001", List.of(new Line("FG-GH200", qty))));
  }

  private TransportRequest shipment(ReservationResult.Success r, int qty) {
    var res = r.reservations().get(0);
    return new TransportRequest("DL-0001/V", TransportKind.SHIPMENT, "PO-0001", "FG-GH200", qty,
        Place.storage(res.locationId()), Place.loading(FlowPilotMasterData.LOADING_ZONE), Priority.NORMAL,
        res.reservationId());
  }

  @Test
  void shipmentCarriesReservedGoodsToTheLoadingZoneAndBooksThemOut() {
    String id = fp.transports().requestTransport(shipment(reserveFinished(4), 4));
    for (int i = 0; i < 6000 && !delivered(id); i++) {
      time.advance(100);
      fp.step(100);
    }
    assertThat(delivered(id)).as("delivered within 10 min").isTrue();
    var event = log.all().stream().filter(e -> e.type().equals(EventTypes.TRANSPORT_DELIVERED)).findFirst().orElseThrow();
    var d = (Payloads.TransportDelivered) event.payload();
    assertThat(d.kind()).isEqualTo(TransportKind.SHIPMENT);
    assertThat(d.to()).isEqualTo(Place.loading("LZ-1"));
    // 6 at start, 4 left with the shipment, nothing stays reserved
    assertThat(fp.stock()).anySatisfy(l -> {
      assertThat(l.materialId()).isEqualTo("FG-GH200");
      assertThat(l.onHand()).isEqualTo(2);
      assertThat(l.reserved()).isZero();
    });
  }

  @Test
  void shipmentNeedsAReservationAndTheLoadingZone() {
    var r = reserveFinished(2);
    var noReservation = new TransportRequest("X/1", TransportKind.SHIPMENT, "PO-0001", "FG-GH200", 2,
        Place.storage("FG-A"), Place.loading("LZ-1"), Priority.NORMAL, null);
    assertThatThrownBy(() -> fp.transports().requestTransport(noReservation)).isInstanceOf(DomainException.class);
    var toMachine = new TransportRequest("X/2", TransportKind.SHIPMENT, "PO-0001", "FG-GH200", 2,
        Place.storage("FG-A"), Place.machineIn("CNC-01"), Priority.NORMAL, r.reservations().get(0).reservationId());
    assertThatThrownBy(() -> fp.transports().requestTransport(toMachine)).isInstanceOf(DomainException.class)
        .hasMessageContaining("Verladezone");
    var unknownZone = new TransportRequest("X/3", TransportKind.SHIPMENT, "PO-0001", "FG-GH200", 2,
        Place.storage("FG-A"), Place.loading("LZ-9"), Priority.NORMAL, r.reservations().get(0).reservationId());
    assertThatThrownBy(() -> fp.transports().requestTransport(unknownZone)).isInstanceOf(DomainException.class);
  }

  private boolean delivered(String transportId) {
    for (NexusEvent e : log.all()) {
      if (e.type().equals(EventTypes.TRANSPORT_DELIVERED)
          && ((Payloads.TransportDelivered) e.payload()).transportId().equals(transportId)) return true;
    }
    return false;
  }
}
