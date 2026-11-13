package io.nexus.smartfactory.planning;

/** Order in which open orders get machine time. */
public enum DispatchRule {
  /** EILIG, HOCH, NORMAL; then due date, creation, id. */
  PRIORITY,
  /** Earliest due date, then id. */
  EDD,
  /** Creation time, then id. */
  FIFO,
  /** Shortest remaining processing time, then id. */
  SPT
}
