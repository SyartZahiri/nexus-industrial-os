package io.nexus.plant.machines;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.DomainException;
import io.nexus.contracts.state.IllegalTransitionException;
import io.nexus.contracts.state.MachineStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class MachineWireTest {
  private static final List<String> IDS = PlantMachines.machineIds();

  @Test
  void everyKindOfReportComesBackUnchanged() {
    List<FeedbackOutbox.Item> items = List.of(
        new FeedbackOutbox.StatusChanged("CNC-01", MachineStatus.IDLE, MachineStatus.SETUP),
        new FeedbackOutbox.FaultRaised("CNC-02", "SPINDLE", "Spindel \"heiß\"", true),
        new FeedbackOutbox.JobFinished("QC-01", "PO-0007", 3, 9, 1),
        new FeedbackOutbox.ConveyorDelivered("QC-01", "PO-0008", 4, 12));
    var parsed = MachineWire.parseItems(MachineWire.items(items), IDS);
    assertThat(parsed.items()).isEqualTo(items);
    assertThat(parsed.rejected()).isEmpty();
  }

  @Test
  void reportsThatMakeNoSenseAreLeftOutAndNamed() {
    String json = "[{\"t\":\"status\",\"m\":\"CNC-01\",\"from\":\"IDLE\",\"to\":\"RUNNING\"},"
        + "{\"t\":\"status\",\"m\":\"CNC-99\",\"from\":\"IDLE\",\"to\":\"RUNNING\"},"
        + "{\"t\":\"status\",\"m\":\"CNC-01\",\"from\":\"IDLE\",\"to\":\"UNKNOWN\"},"
        + "{\"t\":\"finished\",\"m\":\"CNC-01\",\"order\":\"PO-1\",\"step\":1,\"good\":-1,\"scrap\":0},"
        + "{\"t\":\"teleport\",\"m\":\"CNC-01\"},"
        + "{\"t\":\"conveyor\",\"m\":\"QC-01\",\"step\":1,\"qty\":2},"
        + "42]";
    var parsed = MachineWire.parseItems(json, IDS);
    assertThat(parsed.items()).containsExactly(
        new FeedbackOutbox.StatusChanged("CNC-01", MachineStatus.IDLE, MachineStatus.RUNNING));
    assertThat(parsed.rejected()).hasSize(6);
  }

  @Test
  void unreadableReportsAreRejectedAsAWhole() {
    assertThat(MachineWire.parseItems("{", IDS).rejected()).hasSize(1);
    assertThat(MachineWire.parseItems("{}", IDS).rejected()).hasSize(1);
    assertThat(MachineWire.parseItems(null, IDS).rejected()).hasSize(1);
  }

  @Test
  void businessErrorsArriveAsTheSameException() {
    for (DomainException.Kind kind : DomainException.Kind.values()) {
      var original = new DomainException(kind, "Override muss zwischen 50 und 130 % liegen");
      assertThat(MachineWire.exception(MachineWire.error(original)))
          .isInstanceOfSatisfying(DomainException.class, e -> {
            assertThat(e.kind()).isEqualTo(kind);
            assertThat(e.getMessage()).isEqualTo(original.getMessage());
          });
    }
    var refused = new IllegalTransitionException("CNC-01", MachineStatus.RUNNING, MachineStatus.RUNNING);
    assertThat(MachineWire.exception(MachineWire.error(refused)))
        .isInstanceOf(IllegalTransitionException.class).hasMessage(refused.getMessage());
  }

  @Test
  void otherErrorsArriveWithTheirClassNameOnly() {
    assertThat(MachineWire.exception(MachineWire.error(new NullPointerException("internes Detail"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NullPointerException").hasMessageNotContaining("internes Detail");
    assertThat(MachineWire.exception("kaputt")).isInstanceOf(IllegalStateException.class);
  }
}
