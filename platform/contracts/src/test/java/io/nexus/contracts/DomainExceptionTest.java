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

  @Test
  void invalidAndConflictCarryTheirKind() {
    assertThat(DomainException.invalid("Menge muss positiv sein").kind()).isEqualTo(DomainException.Kind.INVALID);
    assertThat(DomainException.conflict("bereits verladen").kind()).isEqualTo(DomainException.Kind.CONFLICT);
  }
}
