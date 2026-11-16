package io.nexus.flowpilot.master;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FlowPilotMasterDataTest {
  private static Set<String> nodeIds() {
    Set<String> ids = new HashSet<>();
    for (var n : FlowPilotMasterData.nodes()) ids.add(n.id());
    return ids;
  }

  @Test
  void graphHasTheGeneratedSize() {
    assertThat(FlowPilotMasterData.nodes()).hasSize(96);  // 92 generated + 4 of the loading zone (V5)
    assertThat(FlowPilotMasterData.edges()).hasSize(116); // 111 generated + 5
  }

  @Test
  void everyEdgeConnectsExistingNodes() {
    Set<String> ids = nodeIds();
    for (var e : FlowPilotMasterData.edges()) {
      assertThat(ids).as(e.id()).contains(e.from(), e.to());
      assertThat(e.id()).isEqualTo(e.from() + ">" + e.to());
      assertThat(e.length()).isPositive();
    }
  }

  @Test
  void everyDockNodeExists() {
    List<String> docks = new ArrayList<>();
    FlowPilotMasterData.locations().forEach(l -> docks.add(l.dockNode()));
    FlowPilotMasterData.chargers().forEach(c -> docks.add(c.nodeId()));
    FlowPilotMasterData.conveyorDocks().forEach(c -> docks.add(c.node()));
    FlowPilotMasterData.agvs().forEach(a -> docks.add(a.nodeId()));
    for (var m : FlowPilotMasterData.machineDocks()) {
      docks.add(m.inNode());
      if (m.outNode() != null) docks.add(m.outNode());
    }
    assertThat(nodeIds()).containsAll(docks);
  }

  @Test
  void everyNodeCanBeEnteredAndLeft() {
    Set<String> in = new HashSet<>();
    Set<String> out = new HashSet<>();
    for (var e : FlowPilotMasterData.edges()) {
      out.add(e.from());
      in.add(e.to());
    }
    for (var n : FlowPilotMasterData.nodes()) {
      assertThat(in).as("into " + n.id()).contains(n.id());
      assertThat(out).as("out of " + n.id()).contains(n.id());
    }
  }

  @Test
  void stockAndFleetMatchTheSeed() {
    assertThat(FlowPilotMasterData.materials()).hasSize(10);
    assertThat(FlowPilotMasterData.locations()).extracting(FlowPilotMasterData.StorageLocation::id)
        .containsExactly("RAW-A", "RAW-B", "FG-A");
    assertThat(FlowPilotMasterData.agvs()).hasSize(6);
    assertThat(FlowPilotMasterData.chargers()).hasSize(6);
    assertThat(FlowPilotMasterData.machineDocks()).hasSize(7);
    assertThat(FlowPilotMasterData.conveyorDocks()).hasSize(4);
    assertThat(FlowPilotMasterData.initialStock()).hasSize(10);
  }
}
