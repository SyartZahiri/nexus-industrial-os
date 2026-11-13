package io.nexus.app.plant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.DomainException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The clock logic of the loop, driven by hand: no thread, no dependence on how fast the machine is. */
class SimulationLoopTest {
  final List<Long> advanced = new ArrayList<>();
  final List<Throwable> reported = new ArrayList<>();
  final SimulationLoop loop = new SimulationLoop(ms -> advanced.add(ms), reported::add);

  long total() {
    return advanced.stream().mapToLong(Long::longValue).sum();
  }

  @Test
  void startsPausedAndOnlyAdvancesWhilePlaying() {
    loop.tick(50);
    assertThat(advanced).isEmpty();
    loop.play();
    for (int i = 0; i < 20; i++) loop.tick(50);          // 1 s at 1x
    assertThat(total()).isEqualTo(1_000);
    loop.pause();
    loop.tick(50);
    assertThat(total()).isEqualTo(1_000);
  }

  @Test
  void speedMultipliesSimulationTimeInWholeSteps() {
    loop.play();
    loop.setSpeed(10);
    for (int i = 0; i < 40; i++) loop.tick(50);          // 2 s real
    assertThat(total()).isEqualTo(20_000);
    assertThat(advanced).allMatch(ms -> ms % 100 == 0);
    loop.setSpeed(0.25);
    for (int i = 0; i < 8; i++) loop.tick(50);            // 0.4 s real = 100 ms simulated
    assertThat(total()).isEqualTo(20_100);
  }

  @Test
  void speedOutsideTheRangeIsRefused() {
    assertThatThrownBy(() -> loop.setSpeed(0)).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> loop.setSpeed(25.5)).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> loop.setSpeed(Double.NaN)).isInstanceOf(DomainException.class);
  }

  /** After a long stall (garbage collection, a slow query) the plant does not race to catch up. */
  @Test
  void backlogIsCappedInsteadOfCaughtUp() {
    loop.play();
    loop.setSpeed(25);
    loop.tick(10_000);                                    // ten seconds late
    assertThat(total()).isEqualTo(SimulationLoop.MAX_STEPS_PER_TICK * 100L);
    loop.tick(50);
    assertThat(total()).isEqualTo(SimulationLoop.MAX_STEPS_PER_TICK * 100L + 1_200);   // owed time was dropped
  }

  @Test
  void aFailingStepPausesTheLoopAndIsReported() {
    var failing = new SimulationLoop(ms -> { throw new IllegalStateException("kaputt"); }, reported::add);
    failing.play();
    failing.tick(100);
    assertThat(failing.running()).isFalse();
    assertThat(reported).singleElement().isInstanceOf(IllegalStateException.class);
    failing.play();                                       // can be started again
    assertThat(failing.running()).isTrue();
  }
}
