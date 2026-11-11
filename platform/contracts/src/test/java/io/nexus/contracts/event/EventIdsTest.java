package io.nexus.contracts.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class EventIdsTest {
  private static List<String> take(EventIds ids, int n) {
    return Stream.generate(ids::next).limit(n).toList();
  }

  @Test
  void idsAreRunNumberDashCounterStartingAtZero() {
    var ids = new EventIds(7);
    assertThat(ids.next()).isEqualTo("7-0");
    assertThat(ids.next()).isEqualTo("7-1");
    assertThat(ids.runId()).isEqualTo(7);
  }

  @Test
  void sameRunGivesTheSameSequence() {
    assertThat(take(new EventIds(3), 100)).isEqualTo(take(new EventIds(3), 100));
  }

  @Test
  void idsOfDifferentRunsNeverCollide() {
    var all = new HashSet<String>();
    all.addAll(take(new EventIds(1), 1000));
    all.addAll(take(new EventIds(11), 1000)); // "1-10" vs "11-0" must not collide either
    assertThat(all).hasSize(2000);
  }
}
