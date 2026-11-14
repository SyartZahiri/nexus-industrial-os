package io.nexus.contracts.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MachineStatusUnknownTest {
  @Test
  void everyStateCanBecomeUnknownAndUnknownCanBecomeEveryState() {
    for (MachineStatus s : MachineStatus.values()) {
      if (s == MachineStatus.UNKNOWN) continue;
      assertThat(MachineStatus.RULES.can(s, MachineStatus.UNKNOWN)).as(s + " → UNKNOWN").isTrue();
      assertThat(MachineStatus.RULES.can(MachineStatus.UNKNOWN, s)).as("UNKNOWN → " + s).isTrue();
    }
  }
}
