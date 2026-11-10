package io.nexus.contracts.state;

public class IllegalTransitionException extends RuntimeException {
  public IllegalTransitionException(String entityId, Enum<?> from, Enum<?> to) {
    super("Ungültiger Zustandsübergang für " + entityId + ": " + from + " → " + to);
  }
}
