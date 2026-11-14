package io.nexus.app.opcua;

import io.nexus.contracts.event.EventBus;
import io.nexus.smartfactory.SmartFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The machines' OPC UA server and the gateway the plant drives them through. */
@Configuration
class OpcUaConfig {
  @Bean(destroyMethod = "stop")
  MachineServer machineServer(@Value("${nexus.opcua.port}") int port) {
    return new MachineServer(port).start();
  }

  /** Also the MachineControl SmartFactory commands the machines with. */
  @Bean(destroyMethod = "close")
  OpcUaMachines machineGateway(MachineServer server, SmartFactory smartFactory, EventBus bus) {
    return OpcUaMachines.connect(server, smartFactory, bus);
  }
}
