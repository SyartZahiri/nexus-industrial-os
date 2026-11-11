package io.nexus.contracts.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class SeededRandomTest {
  private static List<Long> take(SeededRandom r, int n) {
    return LongStream.range(0, n).map(i -> r.nextLong()).boxed().toList();
  }

  /** Published SplitMix64 reference output for seed 0. */
  @Test
  void matchesTheSplitMix64ReferenceSequence() {
    var r = new SeededRandom(0);
    assertThat(r.nextLong()).isEqualTo(0xE220A8397B1DCDAFL);
    assertThat(r.nextLong()).isEqualTo(0x6E789E6AA1B965F4L);
    assertThat(r.nextLong()).isEqualTo(0x06C45D188009454FL);
  }

  @Test
  void sameSeedGivesTheSameSequence() {
    assertThat(take(new SeededRandom(4711), 50)).isEqualTo(take(new SeededRandom(4711), 50));
    assertThat(take(new SeededRandom(4711), 50)).isNotEqualTo(take(new SeededRandom(4712), 50));
  }

  @Test
  void doublesStayInUnitInterval() {
    var r = new SeededRandom(1);
    for (int i = 0; i < 10_000; i++) assertThat(r.nextDouble()).isGreaterThanOrEqualTo(0).isLessThan(1);
  }

  @Test
  void intsCoverTheWholeBoundAndNothingElse() {
    var r = new SeededRandom(2);
    var seen = new HashSet<Integer>();
    for (int i = 0; i < 10_000; i++) seen.add(r.nextInt(6));
    assertThat(seen).containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5);
  }

  @Test
  void nonPositiveBoundIsRejected() {
    assertThatThrownBy(() -> new SeededRandom(1).nextInt(0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rangeStaysWithinItsLimits() {
    var r = new SeededRandom(3);
    for (int i = 0; i < 1000; i++) assertThat(r.range(0.85, 1.2)).isGreaterThanOrEqualTo(0.85).isLessThan(1.2);
  }

  @Test
  void forksAreIndependentOfEachOtherAndOfHowMuchTheParentWasUsed() {
    var fresh = new SeededRandom(4711);
    var used = new SeededRandom(4711);
    take(used, 37);
    assertThat(take(used.fork("flowpilot"), 20)).isEqualTo(take(fresh.fork("flowpilot"), 20));
    assertThat(take(fresh.fork("flowpilot"), 20)).isNotEqualTo(take(fresh.fork("smartfactory"), 20));
  }
}
