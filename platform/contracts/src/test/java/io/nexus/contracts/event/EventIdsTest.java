package io.nexus.contracts.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class EventIdsTest {
  private static List<UUID> take(EventIds ids, int n) {
    return Stream.generate(ids::next).limit(n).toList();
  }

  @Test
  void sameRunAndSeedGiveTheSameSequence() {
    assertThat(take(new EventIds(3, 4711), 100)).isEqualTo(take(new EventIds(3, 4711), 100));
  }

  @Test
  void idsAreUniqueWithinARunAndAcrossRuns() {
    var all = new HashSet<UUID>();
    all.addAll(take(new EventIds(1, 4711), 1000));
    all.addAll(take(new EventIds(2, 4711), 1000));
    assertThat(all).hasSize(2000);
  }

  @Test
  void largeAndNegativeSeedsDoNotCollideAcrossRuns() {
    var all = new HashSet<UUID>();
    for (long seed : new long[] {-1, Long.MAX_VALUE, 0}) {
      all.addAll(take(new EventIds(1, seed), 10));
      all.addAll(take(new EventIds(2, seed), 10));
    }
    // -1 and MAX_VALUE share their low 32 bits and coincide within a run (a run has one seed), never across runs
    assertThat(all).hasSize(40);
  }
}
