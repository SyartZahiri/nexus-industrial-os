package io.nexus.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DomainExceptionTest {
  @Test
  void notFoundNamesTheThingAndItsId() {
    var e = DomainException.notFound("Auftrag", "PO-9");
    assertThat(e.kind()).isEqualTo(DomainException.Kind.NOT_FOUND);
    assertThat(e).hasMessageContaining("Auftrag").hasMessageContaining("PO-9");
  }

  /** The id usually comes from a URL; it must not smuggle control characters or arbitrary length into a message. */
  @Test
  void notFoundNeutralisesTheIdItEchoes() {
    var e = DomainException.notFound("Auftrag", "PO-1\r\n<script>" + "x".repeat(200));
    assertThat(e.getMessage()).doesNotContain("\r").doesNotContain("\n").doesNotContain("<").doesNotContain(">");
    assertThat(e.getMessage().length()).isLessThan(110);
    assertThat(DomainException.notFound("Auftrag", null)).hasMessageContaining("?");
    assertThat(DomainException.notFound("Ereignis", "12-7").getMessage()).isEqualTo("Ereignis 12-7 nicht gefunden");
  }

  @Test
  void invalidAndConflictCarryTheirKind() {
    assertThat(DomainException.invalid("Menge muss positiv sein").kind()).isEqualTo(DomainException.Kind.INVALID);
    assertThat(DomainException.conflict("bereits verladen").kind()).isEqualTo(DomainException.Kind.CONFLICT);
  }
}
