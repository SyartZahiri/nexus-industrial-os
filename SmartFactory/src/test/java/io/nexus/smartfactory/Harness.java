package io.nexus.smartfactory;

import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventIds;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.memory.MemoryEventLog;
import io.nexus.contracts.memory.MemoryIdSource;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.sim.SimTime;
import io.nexus.smartfactory.testkit.FakeMachineControl;
import io.nexus.smartfactory.testkit.FakeMaterialService;
import io.nexus.smartfactory.testkit.FakeTransportService;
import java.util.List;

/** SmartFactory in memory with the fakes of FlowPilot and the machines, as the core tests use it. */
public final class Harness {
  public final MemoryUnitOfWork work = new MemoryUnitOfWork();
  public final MemoryEventLog log = new MemoryEventLog(work);
  public final SimTime time = new SimTime();
  public final EventIds eventIds = new EventIds(1);
  public final EventBus bus = new EventBus(log, work, time, () -> eventIds);
  public final MemoryIdSource ids = new MemoryIdSource(work);
  public final FakeMaterialService materials = new FakeMaterialService();
  public final FakeTransportService transports = new FakeTransportService(bus);
  public final FakeMachineControl machines = new FakeMachineControl();
  public final SmartFactory factory = SmartFactory.inMemory(bus, work, time, ids, materials, transports, machines);

  public List<NexusEvent> events() {
    return log.all();
  }

  public List<NexusEvent> events(String type) {
    return log.all().stream().filter(e -> e.type().equals(type)).toList();
  }

  public List<String> types(String correlationId) {
    return log.all().stream().filter(e -> correlationId.equals(e.correlationId())).map(NexusEvent::type).toList();
  }
}
