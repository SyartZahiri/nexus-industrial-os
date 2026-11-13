package io.nexus.flowpilot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FlowPilotSelfTestTest {
  @Test
  void sameSeedSameOutput() {
    assertThat(FlowPilotSelfTest.run(4711)).isEqualTo(FlowPilotSelfTest.run(4711));
  }

  @Test
  void transportIsDeliveredAroundTheBlockedEdge() {
    String out = FlowPilotSelfTest.run(4711);
    assertThat(out.lines()).hasSize(5);
    assertThat(out).contains("transport.created").contains("route.replanned").contains("transport.delivered");
    assertThat(out).containsPattern("delivered=\\d+ms").containsPattern("battery=[0-9a-f]+")
        .containsPattern("position=[0-9a-f]+,[0-9a-f]+").containsPattern("blocked=\\S+>\\S+");
  }
}
