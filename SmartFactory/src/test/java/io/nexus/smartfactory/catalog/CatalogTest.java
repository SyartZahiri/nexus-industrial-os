package io.nexus.smartfactory.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.Domain.ProcessType;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.port.ProductCatalog.MaterialDemand;
import io.nexus.smartfactory.master.SmartFactoryMasterData;
import io.nexus.smartfactory.master.SmartFactoryMasterData.BomItem;
import io.nexus.smartfactory.master.SmartFactoryMasterData.Machine;
import io.nexus.smartfactory.master.SmartFactoryMasterData.RoutingStep;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogTest {
  private final Catalog catalog = new Catalog();

  @Test
  void demandIsOneEntryPerStepAndMaterialTimesQuantity() {
    assertThat(catalog.demandFor("GH200", 5)).containsExactly(
        new MaterialDemand(1, "RM-GH200", 5), new MaterialDemand(2, "KIT-GH200", 5));
  }

  @Test
  void flangePlateNeedsMaterialOnlyInStepOne() {
    assertThat(catalog.demandFor("FP12", 3)).containsExactly(new MaterialDemand(1, "RM-FP12", 3));
  }

  @Test
  void unknownProductIsNotFound() {
    assertThatThrownBy(() -> catalog.demandFor("XX", 1))
        .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.kind()).isEqualTo(DomainException.Kind.NOT_FOUND));
    assertThatThrownBy(() -> catalog.product("XX")).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> catalog.finishedMaterialId("XX")).isInstanceOf(DomainException.class);
  }

  @Test
  void quantityMustBePositive() {
    for (int q : new int[] {0, -1}) {
      assertThatThrownBy(() -> catalog.demandFor("GH200", q))
          .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.kind()).isEqualTo(DomainException.Kind.INVALID));
    }
  }

  @Test
  void finishedMaterialAndRoutingComeFromMasterData() {
    assertThat(catalog.finishedMaterialId("MT7")).isEqualTo("FG-MT7");
    assertThat(catalog.routing("AW50")).extracting(RoutingStep::process)
        .containsExactly(ProcessType.DREHEN, ProcessType.FRAESEN, ProcessType.PRUEFEN);
  }

  /** Reservation and transport of a material must carry the same quantity. */
  @Test
  void everyMaterialOccursAtMostOncePerProduct() {
    var seen = new HashSet<String>();
    for (BomItem b : SmartFactoryMasterData.bom()) {
      assertThat(seen.add(b.productId() + "/" + b.materialId())).as(b.toString()).isTrue();
    }
  }

  @Test
  void everyStepHasACapableMachine() {
    for (RoutingStep step : SmartFactoryMasterData.routing()) {
      assertThat(SmartFactoryMasterData.machines()).as(step.toString())
          .anyMatch(m -> m.capabilities().contains(step.process()));
      Machine preferred = SmartFactoryMasterData.machines().stream()
          .filter(m -> m.id().equals(step.preferredMachineId())).findFirst().orElseThrow();
      assertThat(preferred.capabilities()).contains(step.process());
    }
  }

  /** Assembly cells drop their parts on a conveyor that feeds QC, so the next step must be the inspection. */
  @Test
  void everyAssemblyStepIsDirectlyFollowedByAnInspection() {
    List<RoutingStep> routing = SmartFactoryMasterData.routing();
    for (int i = 0; i < routing.size(); i++) {
      RoutingStep step = routing.get(i);
      if (step.process() != ProcessType.MONTAGE) continue;
      RoutingStep next = routing.get(i + 1);
      assertThat(next.productId()).isEqualTo(step.productId());
      assertThat(next.seq()).isEqualTo(step.seq() + 1);
      assertThat(next.process()).isEqualTo(ProcessType.PRUEFEN);
    }
  }

  @Test
  void conveyorLinksConnectEveryConveyorMachineToQc() {
    for (Machine m : SmartFactoryMasterData.machines()) {
      boolean linked = SmartFactoryMasterData.conveyorLinks().stream().anyMatch(l -> l.fromMachineId().equals(m.id()));
      assertThat(linked).as(m.id()).isEqualTo(m.outputToConveyor());
    }
    assertThat(SmartFactoryMasterData.conveyorLinks()).allMatch(l -> l.toMachineId().equals("QC-01") && l.conveyorId().equals("CV-03"));
  }

  @Test
  void setupTimesComeFromTheFormerEngine() {
    assertThat(SmartFactoryMasterData.setupMs(io.nexus.contracts.Domain.MachineKind.CNC)).isEqualTo(30_000);
    assertThat(SmartFactoryMasterData.setupMs(io.nexus.contracts.Domain.MachineKind.ROBOT)).isEqualTo(15_000);
    assertThat(SmartFactoryMasterData.setupMs(io.nexus.contracts.Domain.MachineKind.QC)).isZero();
  }
}
