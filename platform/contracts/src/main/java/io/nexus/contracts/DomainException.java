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
    return new DomainException(Kind.NOT_FOUND, what + " " + id + " nicht gefunden");
  }

  public static DomainException invalid(String message) {
    return new DomainException(Kind.INVALID, message);
  }

  public static DomainException conflict(String message) {
    return new DomainException(Kind.CONFLICT, message);
  }
}
