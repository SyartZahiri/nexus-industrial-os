package io.nexus.plant.machines;

import io.nexus.contracts.port.MachineFeedback;
import io.nexus.contracts.sim.SeededRandom;
import io.nexus.sim.Simulation;
import io.nexus.smartfactory.master.SmartFactoryMasterData;
import java.util.ArrayList;
import java.util.List;

/** The machines and conveyors of this plant, from SmartFactory's master data. Used in memory and by the OPC UA server. */
public final class PlantMachines {
  /** Drop and take positions on the collecting conveyor CV-03, from FactoryTwin/src/sim/initialState.ts. */
  private static final double CV03_LENGTH_M = 27;
  private static final double CV03_SPEED_MPS = 0.6;

  private PlantMachines() {}

  /** New machines reporting to feedback, with the random stream new SeededRandom(seed).fork("sim"). */
  public static Simulation newSimulation(long seed, MachineFeedback feedback) {
    List<Simulation.MachineSpec> specs = new ArrayList<>();
    for (SmartFactoryMasterData.Machine m : SmartFactoryMasterData.machines()) {
      specs.add(new Simulation.MachineSpec(m.id(), m.kind()));
    }
    List<Simulation.ConveyorLink> links = new ArrayList<>();
    for (SmartFactoryMasterData.ConveyorLink l : SmartFactoryMasterData.conveyorLinks()) {
      links.add(new Simulation.ConveyorLink(l.fromMachineId(), l.conveyorId(), l.toMachineId(), dropAt(l.fromMachineId()),
          CV03_LENGTH_M, CV03_SPEED_MPS));
    }
    return new Simulation(specs, links, new SeededRandom(seed).fork("sim"), feedback);
  }

  /** Machine ids in id order, as the simulation keeps them. */
  public static List<String> machineIds() {
    List<String> ids = new ArrayList<>();
    for (SmartFactoryMasterData.Machine m : SmartFactoryMasterData.machines()) ids.add(m.id());
    ids.sort(String::compareTo);
    return ids;
  }

  /** Conveyor ids in id order. */
  public static List<String> conveyorIds() {
    List<String> ids = new ArrayList<>();
    for (SmartFactoryMasterData.ConveyorLink l : SmartFactoryMasterData.conveyorLinks()) {
      if (!ids.contains(l.conveyorId())) ids.add(l.conveyorId());
    }
    ids.sort(String::compareTo);
    return ids;
  }

  private static double dropAt(String machineId) {
    return switch (machineId) {
      case "ROB-01" -> 6.7;
      case "ROB-02" -> 15.7;
      default -> throw new IllegalStateException("Keine Ablageposition auf CV-03 für " + machineId);
    };
  }
}
