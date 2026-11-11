package io.nexus.contracts.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventTypesTest {
  /** Every type constant must have a payload record, otherwise two modules would invent their own keys. */
  @Test
  void everyDeclaredTypeHasAPayloadType() throws Exception {
    List<String> declared = new ArrayList<>();
    for (Field f : EventTypes.class.getDeclaredFields()) {
      if (Modifier.isPublic(f.getModifiers()) && f.getType() == String.class) declared.add((String) f.get(null));
    }
    assertThat(declared).hasSize(32).doesNotHaveDuplicates();
    assertThat(EventTypes.all()).containsExactlyInAnyOrderElementsOf(declared);
    for (String type : declared) assertThat(EventTypes.payloadType(type)).as(type).isNotNull();
  }

  @Test
  void crossModuleEventsHaveTheirOwnRecord() {
    assertThat(EventTypes.payloadType(EventTypes.TRANSPORT_DELIVERED)).isEqualTo(Payloads.TransportDelivered.class);
    assertThat(EventTypes.payloadType(EventTypes.MACHINE_STATUS)).isEqualTo(Payloads.MachineStatusChanged.class);
    assertThat(EventTypes.payloadType(EventTypes.MACHINE_FAULT)).isEqualTo(Payloads.MachineFault.class);
    assertThat(EventTypes.payloadType(EventTypes.ORDER_CREATED)).isEqualTo(Payloads.OrderCreated.class);
  }

  /** Set.copyOf iterates in a different order on every JVM start; anything iterating these must be stable. */
  @Test
  void typesAndActiveStatesIterateInDeclarationOrder() {
    assertThat(List.copyOf(EventTypes.all())).startsWith(EventTypes.ORDER_CREATED, EventTypes.ORDER_MATERIAL_PENDING)
        .endsWith(EventTypes.DECISION, EventTypes.SYSTEM_CONSUMER_FAILED);
    assertThat(List.copyOf(io.nexus.contracts.state.TransportStatus.ACTIVE)).containsExactly(
        io.nexus.contracts.state.TransportStatus.ASSIGNED, io.nexus.contracts.state.TransportStatus.TO_PICKUP,
        io.nexus.contracts.state.TransportStatus.LOADING, io.nexus.contracts.state.TransportStatus.IN_TRANSIT,
        io.nexus.contracts.state.TransportStatus.UNLOADING);
  }

  @Test
  void unknownTypeIsRejected() {
    assertThatThrownBy(() -> EventTypes.payloadType("gibt.es.nicht")).isInstanceOf(IllegalArgumentException.class);
  }
}
