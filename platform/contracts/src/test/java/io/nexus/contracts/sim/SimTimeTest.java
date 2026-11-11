package io.nexus.contracts.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SimTimeTest {
  @Test
  void advancesAndResets() {
    var t = new SimTime();
    t.advance(100);
    t.advance(100);
    assertThat(t.nowMs()).isEqualTo(200);
    t.reset();
    assertThat(t.nowMs()).isZero();
  }

  @Test
  void neverRunsBackwards() {
    assertThatThrownBy(() -> new SimTime().advance(-1)).isInstanceOf(IllegalArgumentException.class);
  }
}
