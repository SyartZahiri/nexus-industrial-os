package io.nexus.contracts.port;

/** Readable, deterministic business ids such as PO-0001 or TO-0042. */
public interface IdSource {
  /**
   * Next id for the prefix: prefix + "-" + counter, zero-padded to at least 4 digits, starting at 1.
   * Counts inside the caller's unit of work: a rollback hands the number out again.
   */
  String next(String prefix);

  /** Restarts every counter at 1. Only called by the simulation reset, which also clears the business tables. */
  void reset();

  /** Shared formatting, so both runtimes produce the same text (no String.format in the core). */
  static String format(String prefix, long counter) {
    String digits = Long.toString(counter);
    StringBuilder out = new StringBuilder(prefix).append('-');
    for (int i = digits.length(); i < 4; i++) out.append('0');
    return out.append(digits).toString();
  }
}
