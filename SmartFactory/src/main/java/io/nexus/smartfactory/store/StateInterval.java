package io.nexus.smartfactory.store;

import io.nexus.contracts.state.MachineStatus;

/** @param toMs null while the machine is still in the state */
public record StateInterval(String machineId, MachineStatus status, long fromMs, Long toMs) {}
