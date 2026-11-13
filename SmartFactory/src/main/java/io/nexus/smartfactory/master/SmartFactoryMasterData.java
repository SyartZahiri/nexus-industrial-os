package io.nexus.smartfactory.master;

import static io.nexus.contracts.Domain.ProcessType.DREHEN;
import static io.nexus.contracts.Domain.ProcessType.FRAESEN;
import static io.nexus.contracts.Domain.ProcessType.MONTAGE;
import static io.nexus.contracts.Domain.ProcessType.PRUEFEN;

import io.nexus.contracts.Domain.MachineKind;
import io.nexus.contracts.Domain.ProcessType;
import java.util.List;

/**
 * Master data of SmartFactory as code, for the browser runtime that has no database.
 * Must equal V3__master_data.sql; MasterDataMatchesDatabaseTest in platform/app checks that.
 */
public final class SmartFactoryMasterData {
  private SmartFactoryMasterData() {}

  public record Product(String id, String name, String shortName, String color, String finishedMaterialId) {}

  public record RoutingStep(String productId, int seq, ProcessType process, int baseTimeS, String preferredMachineId) {}

  public record BomItem(String productId, int seq, String materialId, int quantity) {}

  public record Machine(String id, String name, MachineKind kind, String model, List<ProcessType> capabilities,
                        double x, double z, boolean outputToConveyor) {}

  /** A machine that hands over to a conveyor feeds exactly this machine. */
  public record ConveyorLink(String fromMachineId, String conveyorId, String toMachineId) {}

  private static final List<Product> PRODUCTS = List.of(
      new Product("AW50", "Antriebswelle AW-50", "AW-50", "#d95926", "FG-AW50"),
      new Product("FP12", "Flanschplatte FP-12", "FP-12", "#199e70", "FG-FP12"),
      new Product("GH200", "Getriebegehäuse GH-200", "GH-200", "#3987e5", "FG-GH200"),
      new Product("MT7", "Motorträger MT-7", "MT-7", "#c98500", "FG-MT7"));

  private static final List<RoutingStep> ROUTING = List.of(
      new RoutingStep("AW50", 1, DREHEN, 70, "CNC-03"),
      new RoutingStep("AW50", 2, FRAESEN, 45, "CNC-02"),
      new RoutingStep("AW50", 3, PRUEFEN, 22, "QC-01"),
      new RoutingStep("FP12", 1, FRAESEN, 55, "CNC-02"),
      new RoutingStep("FP12", 2, PRUEFEN, 18, "QC-01"),
      new RoutingStep("GH200", 1, FRAESEN, 85, "CNC-01"),
      new RoutingStep("GH200", 2, MONTAGE, 65, "ROB-01"),
      new RoutingStep("GH200", 3, PRUEFEN, 28, "QC-01"),
      new RoutingStep("MT7", 1, DREHEN, 60, "CNC-04"),
      new RoutingStep("MT7", 2, MONTAGE, 80, "ROB-02"),
      new RoutingStep("MT7", 3, PRUEFEN, 30, "QC-01"));

  private static final List<BomItem> BOM = List.of(
      new BomItem("AW50", 1, "RM-AW50", 1),
      new BomItem("FP12", 1, "RM-FP12", 1),
      new BomItem("GH200", 1, "RM-GH200", 1),
      new BomItem("GH200", 2, "KIT-GH200", 1),
      new BomItem("MT7", 1, "RM-MT7", 1),
      new BomItem("MT7", 2, "KIT-MT7", 1));

  private static final List<Machine> MACHINES = List.of(
      new Machine("CNC-01", "Fräszentrum 1", MachineKind.CNC, "VMC-850 · 3-Achs-Vertikal", List.of(FRAESEN), -12, -10, false),
      new Machine("CNC-02", "Fräszentrum 2", MachineKind.CNC, "VMC-850 · 3-Achs-Vertikal", List.of(FRAESEN), -4, -10, false),
      new Machine("CNC-03", "Drehzentrum", MachineKind.CNC, "TC-400 · CNC-Drehmaschine", List.of(DREHEN), 4, -10, false),
      new Machine("CNC-04", "5-Achs-Zentrum", MachineKind.CNC, "UMC-500 · 5-Achs-Universal", List.of(DREHEN, FRAESEN), 12, -10, false),
      new Machine("QC-01", "Qualitätsprüfung", MachineKind.QC, "Vision- & Laser-Messzelle", List.of(PRUEFEN), 14.8, 12.6, false),
      new Machine("ROB-01", "Montagezelle 1", MachineKind.ROBOT, "Knickarm 6-Achs · 12 kg", List.of(MONTAGE), -7.3, 10.9, true),
      new Machine("ROB-02", "Montagezelle 2", MachineKind.ROBOT, "Knickarm 6-Achs · 12 kg", List.of(MONTAGE), 1.7, 10.9, true));

  private static final List<ConveyorLink> CONVEYOR_LINKS = List.of(
      new ConveyorLink("ROB-01", "CV-03", "QC-01"),
      new ConveyorLink("ROB-02", "CV-03", "QC-01"));

  /** Ordered by id. */
  public static List<Product> products() {
    return PRODUCTS;
  }

  /** Ordered by product, seq. */
  public static List<RoutingStep> routing() {
    return ROUTING;
  }

  /** Ordered by product, seq, material. */
  public static List<BomItem> bom() {
    return BOM;
  }

  /** Ordered by id. */
  public static List<Machine> machines() {
    return MACHINES;
  }

  public static List<ConveyorLink> conveyorLinks() {
    return CONVEYOR_LINKS;
  }

  public static Machine machine(String id) {
    for (Machine m : MACHINES) if (m.id().equals(id)) return m;
    return null;
  }

  /** Setup time from FactoryTwin/src/sim/config.ts (MACHINE_PHYSICS.setupTime). */
  public static long setupMs(MachineKind kind) {
    return switch (kind) {
      case CNC -> 30_000;
      case ROBOT -> 15_000;
      case QC -> 0;
    };
  }
}
