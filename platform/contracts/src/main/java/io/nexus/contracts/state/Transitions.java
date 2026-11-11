package io.nexus.contracts.state;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Transition table of a state machine. Immutable after build(). */
public final class Transitions<S extends Enum<S>> {
  private final Map<S, Set<S>> allowed;

  private Transitions(Map<S, Set<S>> allowed) {
    this.allowed = allowed;
  }

  public boolean can(S from, S to) {
    return allowed.getOrDefault(from, Set.of()).contains(to);
  }

  /** Returns {@code to} if the transition is legal, otherwise throws. Staying in the same state is a no-op. */
  public S move(String entityId, S from, S to) {
    if (from != to && !can(from, to)) throw new IllegalTransitionException(entityId, from, to);
    return to;
  }

  public static <S extends Enum<S>> Builder<S> of(Class<S> type) {
    return new Builder<>(type);
  }

  public static final class Builder<S extends Enum<S>> {
    private final Class<S> type;
    private final Map<S, Set<S>> allowed;

    private Builder(Class<S> type) {
      this.type = type;
      this.allowed = new EnumMap<>(type);
    }

    @SafeVarargs
    public final Builder<S> allow(S from, S... to) {
      allowed.computeIfAbsent(from, k -> EnumSet.noneOf(type)).addAll(Set.of(to));
      return this;
    }

    public Builder<S> allowFromAll(Set<S> from, S to) {
      for (S f : from) allow(f, to);
      return this;
    }

    public Transitions<S> build() {
      return new Transitions<>(allowed);
    }
  }
}
