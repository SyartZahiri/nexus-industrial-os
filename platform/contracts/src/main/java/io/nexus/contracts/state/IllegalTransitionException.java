package io.nexus.contracts.state;

public class IllegalTransitionException extends RuntimeException {
  private final String entityId;
  private final Enum<?> from;
  private final Enum<?> to;

  public IllegalTransitionException(String entityId, Enum<?> from, Enum<?> to) {
    super("Ungültiger Zustandsübergang für " + entityId + ": " + from + " → " + to);
    this.entityId = entityId;
    this.from = from;
    this.to = to;
  }

  public String entityId() {
    return entityId;
  }

  public Enum<?> from() {
    return from;
  }

  public Enum<?> to() {
    return to;
  }
}
