package io.nexus.sim;

import io.nexus.contracts.Domain.MachineKind;
import java.util.ArrayList;
import java.util.List;

/** Machine fault types from FactoryTwin/src/sim/config.ts (FAULT_TYPES); conveyor and AGV types live elsewhere. */
record FaultType(String id, String label, long repairTimeS, boolean scrapsWorkpiece, List<MachineKind> kinds) {
  private static final List<MachineKind> ALL = List.of(MachineKind.CNC, MachineKind.ROBOT, MachineKind.QC);

  static final List<FaultType> ALL_TYPES = List.of(
      new FaultType("OVERTEMP", "Spindel-Übertemperatur", 240, false, List.of(MachineKind.CNC)),
      new FaultType("TOOL", "Werkzeugbruch", 150, true, List.of(MachineKind.CNC)),
      new FaultType("SERVO", "Achsantrieb gestört (Servo)", 300, false, List.of(MachineKind.CNC, MachineKind.ROBOT)),
      new FaultType("GRIPPER", "Greiferfehler", 120, false, List.of(MachineKind.ROBOT)),
      new FaultType("COLLISION", "Kollisionserkennung", 200, true, List.of(MachineKind.ROBOT)),
      new FaultType("VISION", "Sensorstörung Kamera", 90, false, List.of(MachineKind.QC)),
      new FaultType("COMM", "Kommunikationsfehler (Feldbus)", 60, false, ALL),
      new FaultType("ESTOP", "Not-Halt ausgelöst", 45, false, ALL));

  /** Types an operator may trigger on a machine of this kind. */
  static List<FaultType> forKind(MachineKind kind) {
    List<FaultType> out = new ArrayList<>();
    for (FaultType f : ALL_TYPES) if (f.kinds().contains(kind)) out.add(f);
    return out;
  }

  /** Types that happen by themselves while running: no thermal trip (that has its own model) and no e-stop. */
  static List<FaultType> randomForKind(MachineKind kind) {
    List<FaultType> out = new ArrayList<>();
    for (FaultType f : forKind(kind)) if (!f.id().equals("OVERTEMP") && !f.id().equals("ESTOP")) out.add(f);
    return out;
  }
}
