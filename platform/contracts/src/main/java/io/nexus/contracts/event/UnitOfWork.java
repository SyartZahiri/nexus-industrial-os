package io.nexus.contracts.event;

/** Transaction boundary of the runtime: a database transaction on the server, an undo scope in the browser. */
public interface UnitOfWork {
  /** Runs after the current unit commits; immediately if none is active. Not run if the unit rolls back. */
  void afterCommit(Runnable action);

  /**
   * Runs the action in a unit of its own, independent of any surrounding one.
   * If the action throws, everything it wrote is undone and the exception is rethrown.
   */
  void inNewTransaction(Runnable action);
}
