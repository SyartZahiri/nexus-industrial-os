package io.nexus.smartfactory.testkit;

import io.nexus.contracts.port.MachineControl;
import java.util.ArrayList;
import java.util.List;

/** MachineControl stand-in: records jobs and commands. The test answers through MachineFeedback. */
public final class FakeMachineControl implements MachineControl {
  private final List<MachineJob> jobs = new ArrayList<>();
  private final List<String> commands = new ArrayList<>();

  @Override public void start(String machineId) { commands.add("start " + machineId); }
  @Override public void stop(String machineId) { commands.add("stop " + machineId); }
  @Override public void injectFault(String machineId, String faultTypeId) { commands.add("fault " + machineId + " " + faultTypeId); }
  @Override public void repair(String machineId) { commands.add("repair " + machineId); }
  @Override public void maintain(String machineId) { commands.add("maintain " + machineId); }
  @Override public void startJob(MachineJob job) { jobs.add(job); }

  public List<MachineJob> jobs() {
    return List.copyOf(jobs);
  }

  public List<String> commands() {
    return List.copyOf(commands);
  }
}
