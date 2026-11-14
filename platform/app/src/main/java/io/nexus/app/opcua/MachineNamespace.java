package io.nexus.app.opcua;

import io.nexus.contracts.port.MachineControl.MachineJob;
import io.nexus.plant.machines.PlantMachines;
import io.nexus.sim.SimRules;
import io.nexus.sim.Simulation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.core.ValueRanks;
import org.eclipse.milo.opcua.sdk.server.ManagedNamespaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.identity.Identity;
import org.eclipse.milo.opcua.sdk.server.items.DataItem;
import org.eclipse.milo.opcua.sdk.server.items.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.methods.AbstractMethodInvocationHandler;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaMethodNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.structured.Argument;

/**
 * The nodes of the machine server: values per machine and conveyor, and the plant object with its methods. The
 * method lambdas read their arguments only when they run inside execute, so a wrong type or a missing number ends as
 * an error answer instead of Bad_InternalError.
 */
final class MachineNamespace extends ManagedNamespaceWithLifecycle {
  static final List<String> MACHINE_VALUES = List.of("Status", "OrderId", "StepSeq", "Progress", "Temperature",
      "Vibration", "Power", "Spindle", "Wear", "FaultLabel");
  static final List<String> CONVEYOR_VALUES = List.of("OrderIds", "StepSeqs", "Positions");
  private static final int SCALAR = -1;
  private static final int ONE_DIMENSION = 1;
  private static final Argument[] OUTPUTS = {argument("reports", NodeIds.String), argument("error", NodeIds.String)};

  private final MachineServer owner;
  private final SubscriptionModel subscriptions;
  private final Map<String, UaVariableNode> variables = new HashMap<>();

  MachineNamespace(OpcUaServer server, MachineServer owner) {
    super(server, MachineServer.NAMESPACE);
    this.owner = owner;
    subscriptions = new SubscriptionModel(server, this);
    getLifecycleManager().addLifecycle(subscriptions);
    getLifecycleManager().addStartupTask(this::build);
  }

  private void build() {
    UaFolderNode machines = folder("Machines", null);
    for (String id : PlantMachines.machineIds()) {
      UaFolderNode machine = folder("Machines/" + id, machines);
      for (String name : MACHINE_VALUES) {
        NodeId type = switch (name) {
          case "Status", "OrderId", "FaultLabel" -> NodeIds.String;
          case "StepSeq" -> NodeIds.Int32;
          default -> NodeIds.Double;
        };
        variable(machine, "Machines/" + id + "/" + name, type, SCALAR);
      }
    }
    UaFolderNode conveyors = folder("Conveyors", null);
    for (String id : PlantMachines.conveyorIds()) {
      UaFolderNode conveyor = folder("Conveyors/" + id, conveyors);
      variable(conveyor, "Conveyors/" + id + "/OrderIds", NodeIds.String, ONE_DIMENSION);
      variable(conveyor, "Conveyors/" + id + "/StepSeqs", NodeIds.Int32, ONE_DIMENSION);
      variable(conveyor, "Conveyors/" + id + "/Positions", NodeIds.Double, ONE_DIMENSION);
    }
    UaFolderNode plant = folder("Plant", null);
    method(plant, "Reset", in -> () -> owner.newMachines((Long) in[1].value()), argument("seed", NodeIds.Int64));
    method(plant, "Step", in -> () -> owner.sim().step((Long) in[1].value()), argument("dtMs", NodeIds.Int64));
    method(plant, "SetRules", in -> () -> owner.sim().setRules(new SimRules((Boolean) in[1].value(),
            (Boolean) in[2].value(), (Boolean) in[3].value(), (Double) in[4].value())),
        argument("randomFaults", NodeIds.Boolean), argument("autoRepair", NodeIds.Boolean),
        argument("autoMaintenance", NodeIds.Boolean), argument("maintenanceThreshold", NodeIds.Double));
    method(plant, "Configure", in -> () -> owner.sim().configure((String) in[1].value(), (Integer) in[2].value(),
            (Boolean) in[3].value()),
        argument("machineId", NodeIds.String), argument("overridePercent", NodeIds.Int32),
        argument("coolant", NodeIds.Boolean));
    method(plant, "Start", in -> () -> owner.sim().start((String) in[1].value()), argument("machineId", NodeIds.String));
    method(plant, "Stop", in -> () -> owner.sim().stop((String) in[1].value()), argument("machineId", NodeIds.String));
    method(plant, "Repair", in -> () -> owner.sim().repair((String) in[1].value()), argument("machineId", NodeIds.String));
    method(plant, "Maintain", in -> () -> owner.sim().maintain((String) in[1].value()),
        argument("machineId", NodeIds.String));
    method(plant, "InjectFault", in -> () -> owner.sim().injectFault((String) in[1].value(), (String) in[2].value()),
        argument("machineId", NodeIds.String), argument("faultTypeId", NodeIds.String));
    method(plant, "StartJob", in -> () -> owner.sim().startJob(new MachineJob((String) in[1].value(),
            (String) in[2].value(), (Integer) in[3].value(), (String) in[4].value(), (Integer) in[5].value(),
            (Long) in[6].value(), (Long) in[7].value())),
        argument("machineId", NodeIds.String), argument("orderId", NodeIds.String), argument("stepSeq", NodeIds.Int32),
        argument("productId", NodeIds.String), argument("quantity", NodeIds.Int32), argument("setupMs", NodeIds.Int64),
        argument("processMs", NodeIds.Int64));
  }

  /** Writes the machines' current values; called after every method, under the server's lock. */
  void publish(Simulation s) {
    for (Simulation.MachineView v : s.machines()) {
      String p = "Machines/" + v.id() + "/";
      set(p + "Status", Variant.ofString(v.status().name()));
      set(p + "OrderId", Variant.ofString(v.orderId()));
      set(p + "StepSeq", Variant.ofInt32(v.stepSeq()));
      set(p + "Progress", Variant.ofDouble(v.progress()));
      set(p + "Temperature", Variant.ofDouble(v.temperature()));
      set(p + "Vibration", Variant.ofDouble(v.vibration()));
      set(p + "Power", Variant.ofDouble(v.power()));
      set(p + "Spindle", Variant.ofDouble(v.spindle()));
      set(p + "Wear", Variant.ofDouble(v.wear()));
      set(p + "FaultLabel", Variant.ofString(v.faultLabel()));
    }
    for (Simulation.ConveyorView c : s.conveyors()) {
      int n = c.items().size();
      String[] orders = new String[n];
      int[] steps = new int[n];
      double[] positions = new double[n];
      for (int i = 0; i < n; i++) {
        Simulation.ConveyorItem item = c.items().get(i);
        orders[i] = item.orderId();
        steps[i] = item.stepSeq();
        positions[i] = item.positionM();
      }
      String p = "Conveyors/" + c.conveyorId() + "/";
      set(p + "OrderIds", Variant.ofStringArray(orders));
      set(p + "StepSeqs", Variant.ofInt32Array(steps));
      set(p + "Positions", Variant.ofDoubleArray(positions));
    }
  }

  void override(String path, DataValue value) {
    variables.get(path).setValue(value);
  }

  private void set(String path, Variant value) {
    variables.get(path).setValue(new DataValue(value));
  }

  private UaFolderNode folder(String path, UaFolderNode parent) {
    String name = path.substring(path.lastIndexOf('/') + 1);
    var folder = new UaFolderNode(getNodeContext(), newNodeId(path), newQualifiedName(name), LocalizedText.english(name));
    getNodeManager().addNode(folder);
    if (parent == null) {
      folder.addReference(new Reference(folder.getNodeId(), NodeIds.Organizes, NodeIds.ObjectsFolder.expanded(), false));
    } else {
      parent.addOrganizes(folder);
    }
    return folder;
  }

  private void variable(UaFolderNode parent, String path, NodeId type, int valueRank) {
    String name = path.substring(path.lastIndexOf('/') + 1);
    UaVariableNode node = new UaVariableNode.UaVariableNodeBuilder(getNodeContext())
        .setNodeId(newNodeId(path)).setBrowseName(newQualifiedName(name)).setDisplayName(LocalizedText.english(name))
        .setDataType(type).setValueRank(valueRank).setTypeDefinition(NodeIds.BaseDataVariableType)
        .setAccessLevel(AccessLevel.READ_ONLY).setUserAccessLevel(AccessLevel.READ_ONLY).build();
    node.setValue(new DataValue(Variant.NULL_VALUE));
    getNodeManager().addNode(node);
    parent.addOrganizes(node);
    variables.put(path, node);
  }

  /** A method of the plant object: the call id first, then its own arguments; answers reports and error. */
  private void method(UaFolderNode plant, String name, Function<Variant[], Runnable> command, Argument... arguments) {
    UaMethodNode node = UaMethodNode.builder(getNodeContext()).setNodeId(newNodeId("Plant/" + name))
        .setBrowseName(newQualifiedName(name)).setDisplayName(LocalizedText.english(name)).build();
    Argument[] inputs = new Argument[arguments.length + 1];
    inputs[0] = argument("callId", NodeIds.Int64);
    System.arraycopy(arguments, 0, inputs, 1, arguments.length);
    var handler = new AbstractMethodInvocationHandler(node) {
      @Override
      public Argument[] getInputArguments() {
        return inputs;
      }

      @Override
      public Argument[] getOutputArguments() {
        return OUTPUTS;
      }

      @Override
      protected Variant[] invoke(InvocationContext context, Variant[] in) throws UaException {
        boolean gateway = context.getSession().map(s -> s.getIdentity() instanceof Identity.UsernameIdentity u
            && MachineServer.GATEWAY_USER.equals(u.getUsername())).orElse(false);
        if (!gateway) throw new UaException(StatusCodes.Bad_UserAccessDenied);
        return owner.execute((Long) in[0].value(), command.apply(in));
      }
    };
    node.setInputArguments(inputs);
    node.setOutputArguments(OUTPUTS);
    node.setInvocationHandler(handler);
    getNodeManager().addNode(node);
    node.addReference(new Reference(node.getNodeId(), NodeIds.HasComponent, plant.getNodeId().expanded(), false));
  }

  private static Argument argument(String name, NodeId type) {
    return new Argument(name, type, ValueRanks.Scalar, null, LocalizedText.english(name));
  }

  @Override public void onDataItemsCreated(List<DataItem> items) { subscriptions.onDataItemsCreated(items); }

  @Override public void onDataItemsModified(List<DataItem> items) { subscriptions.onDataItemsModified(items); }

  @Override public void onDataItemsDeleted(List<DataItem> items) { subscriptions.onDataItemsDeleted(items); }

  @Override public void onMonitoringModeChanged(List<MonitoredItem> items) { subscriptions.onMonitoringModeChanged(items); }
}
