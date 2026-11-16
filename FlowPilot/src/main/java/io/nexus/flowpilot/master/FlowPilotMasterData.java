package io.nexus.flowpilot.master;

import java.util.List;

/**
 * FlowPilot's master data in the core, because the browser has no database. Same rows as V2/V3;
 * MasterDataMatchesDatabaseTest in platform/app fails the build if the two drift apart.
 */
public final class FlowPilotMasterData {
  private FlowPilotMasterData() {}

  public record Material(String id, String name, String kind, int reorderPoint, int reorderQty) {}

  public record StorageLocation(String id, String name, String zone, String dockNode, int capacity) {}

  public record StockSeed(String materialId, String locationId, int onHand) {}

  public record Node(String id, double x, double z, boolean dock) {}

  public record Edge(String id, String from, String to, double length) {}

  public record AgvSeed(String id, String name, String nodeId) {}

  public record Charger(String id, String nodeId) {}

  /** Where AGVs deliver to (in) and pick up from (out) a machine; out is null for conveyor handover. */
  public record MachineDock(String machineId, String inNode, String outNode) {}

  public record ConveyorDock(String conveyorId, String node) {}

  private static final List<Material> MATERIALS = List.of(
      new Material("RM-GH200", "Gussrohling GH-200", "RAW", 12, 40),
      new Material("RM-AW50", "Rundstahl AW-50", "RAW", 12, 40),
      new Material("RM-FP12", "Plattenzuschnitt FP-12", "RAW", 12, 40),
      new Material("RM-MT7", "Schmiederohling MT-7", "RAW", 12, 40),
      new Material("KIT-GH200", "Lager- und Dichtsatz GH-200", "KIT", 8, 30),
      new Material("KIT-MT7", "Befestigungssatz MT-7", "KIT", 8, 30),
      new Material("FG-GH200", "Getriebegehäuse GH-200", "FINISHED", 0, 0),
      new Material("FG-AW50", "Antriebswelle AW-50", "FINISHED", 0, 0),
      new Material("FG-FP12", "Flanschplatte FP-12", "FINISHED", 0, 0),
      new Material("FG-MT7", "Motorträger MT-7", "FINISHED", 0, 0));

  private static final List<StorageLocation> LOCATIONS = List.of(
      new StorageLocation("RAW-A", "Rohlager Regal A", "RAW", "D_RAW", 240),
      new StorageLocation("RAW-B", "Rohlager Regal B", "RAW", "D_RAW", 120),
      new StorageLocation("FG-A", "Fertiglager Regal A", "FINISHED", "D_FG", 160));

  private static final List<StockSeed> STOCK = List.of(
      new StockSeed("RM-GH200", "RAW-A", 30), new StockSeed("RM-AW50", "RAW-A", 30),
      new StockSeed("RM-FP12", "RAW-A", 30), new StockSeed("RM-MT7", "RAW-A", 30),
      new StockSeed("KIT-GH200", "RAW-B", 24), new StockSeed("KIT-MT7", "RAW-B", 24),
      new StockSeed("FG-GH200", "FG-A", 6), new StockSeed("FG-AW50", "FG-A", 4),
      new StockSeed("FG-FP12", "FG-A", 8), new StockSeed("FG-MT7", "FG-A", 3));

  private static final List<AgvSeed> AGVS = List.of(
      new AgvSeed("AGV-01", "Transportroboter 1", "D_CH1"), new AgvSeed("AGV-02", "Transportroboter 2", "D_CH2"),
      new AgvSeed("AGV-03", "Transportroboter 3", "D_CH3"), new AgvSeed("AGV-04", "Transportroboter 4", "D_CH4"),
      new AgvSeed("AGV-05", "Transportroboter 5", "D_CH5"), new AgvSeed("AGV-06", "Transportroboter 6", "D_CH6"));

  private static final List<Charger> CHARGERS = List.of(
      new Charger("CH-1", "D_CH1"), new Charger("CH-2", "D_CH2"), new Charger("CH-3", "D_CH3"),
      new Charger("CH-4", "D_CH4"), new Charger("CH-5", "D_CH5"), new Charger("CH-6", "D_CH6"));

  private static final List<MachineDock> MACHINE_DOCKS = List.of(
      new MachineDock("CNC-01", "D_CNC01", "D_CNC01"), new MachineDock("CNC-02", "D_CNC02", "D_CNC02"),
      new MachineDock("CNC-03", "D_CNC03", "D_CNC03"), new MachineDock("CNC-04", "D_CNC04", "D_CNC04"),
      new MachineDock("ROB-01", "D_CV01", null), new MachineDock("ROB-02", "D_CV02", null),
      new MachineDock("QC-01", "D_CV03", "D_CV04"));

  private static final List<ConveyorDock> CONVEYOR_DOCKS = List.of(
      new ConveyorDock("CV-01", "D_CV01"), new ConveyorDock("CV-02", "D_CV02"),
      new ConveyorDock("CV-03", "D_CV03"), new ConveyorDock("CV-04", "D_CV04"));

  public static List<Material> materials() { return MATERIALS; }

  public static List<StorageLocation> locations() { return LOCATIONS; }

  public static List<StockSeed> initialStock() { return STOCK; }

  public static List<Node> nodes() { return PathData.NODES; }

  public static List<Edge> edges() { return PathData.EDGES; }

  public static List<AgvSeed> agvs() { return AGVS; }

  public static List<Charger> chargers() { return CHARGERS; }

  public static List<MachineDock> machineDocks() { return MACHINE_DOCKS; }

  public static List<ConveyorDock> conveyorDocks() { return CONVEYOR_DOCKS; }

  /** Where RouteMind's vehicles are loaded; reached through dockNode. */
  public record LoadingZone(String id, String name, String dockNode) {}

  public static final String LOADING_ZONE = "LZ-1";
  private static final List<LoadingZone> LOADING_ZONES =
      List.of(new LoadingZone(LOADING_ZONE, "Verladezone Süd", "D_LZ"));

  public static List<LoadingZone> loadingZones() { return LOADING_ZONES; }
}
