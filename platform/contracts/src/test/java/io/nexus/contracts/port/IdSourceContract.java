package io.nexus.contracts.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.event.UnitOfWork;
import org.junit.jupiter.api.Test;

/** Behaviour every IdSource must show; runs against memory here and PostgreSQL in platform/app. */
public abstract class IdSourceContract {
  protected abstract IdSource ids();

  protected abstract UnitOfWork work();

  /** Puts the counter of the prefix to the given value, so the next id is value + 1. */
  protected abstract void presetCounter(String prefix, long value);

  @Test
  void firstIdIsOneAndPadded() {
    assertThat(ids().next("PO")).isEqualTo("PO-0001");
    assertThat(ids().next("PO")).isEqualTo("PO-0002");
  }

  @Test
  void prefixesCountIndependently() {
    ids().next("PO");
    ids().next("PO");
    assertThat(ids().next("TO")).isEqualTo("TO-0001");
  }

  @Test
  void resetStartsAgainAtOne() {
    ids().next("PO");
    ids().next("TO");
    ids().reset();
    assertThat(ids().next("PO")).isEqualTo("PO-0001");
    assertThat(ids().next("TO")).isEqualTo("TO-0001");
  }

  @Test
  void rolledBackUnitReturnsTheNumber() {
    ids().next("PO");
    String[] taken = new String[1];
    assertThatThrownBy(() -> work().inNewTransaction(() -> {
      taken[0] = ids().next("PO");
      throw new IllegalStateException("boom");
    })).hasMessage("boom");
    assertThat(taken[0]).isEqualTo("PO-0002");
    assertThat(ids().next("PO")).isEqualTo("PO-0002");
  }

  @Test
  void widensBeyondFourDigits() {
    presetCounter("PO", 9999);
    assertThat(ids().next("PO")).isEqualTo("PO-10000");
  }
}
