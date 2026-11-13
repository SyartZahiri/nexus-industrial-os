package io.nexus.smartfactory.store;

import io.nexus.contracts.state.MachineStatus;

/** Operating state of a machine as SmartFactory knows it from the machine's feedback. */
public record MachineRecord(String id, MachineStatus status, String lastProductId, int version) {}
