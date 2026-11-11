package io.nexus.contracts;

/** A business error whose message is meant for the user. Every runtime maps the kind to its own error form. */
public class DomainException extends RuntimeException {
  public enum Kind { NOT_FOUND, INVALID, CONFLICT }

  private final Kind kind;

  public DomainException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  public static DomainException notFound(String what, String id) {
    return new DomainException(Kind.NOT_FOUND, what + " " + safeId(id) + " nicht gefunden");
  }

  private static final int MAX_ID_LENGTH = 64;

  /**
   * Ids often come straight from a request. Only characters ids actually use are echoed, at most 64 of them.
   * A plain loop instead of a regex, because the core also runs in the browser build.
   */
  static String safeId(String id) {
    if (id == null) return "?";
    var out = new StringBuilder();
    for (int i = 0; i < id.length() && out.length() < MAX_ID_LENGTH; i++) {
      char c = id.charAt(i);
      boolean allowed = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
          || c == '-' || c == '_' || c == '.' || c == ':';
      out.append(allowed ? c : '?');
    }
    if (id.length() > MAX_ID_LENGTH) out.append("…");
    return out.toString();
  }

  public static DomainException invalid(String message) {
    return new DomainException(Kind.INVALID, message);
  }

  public static DomainException conflict(String message) {
    return new DomainException(Kind.CONFLICT, message);
  }
}
