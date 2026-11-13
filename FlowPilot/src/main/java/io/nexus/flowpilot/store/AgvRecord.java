package io.nexus.flowpilot.store;

import io.nexus.contracts.state.AgvStatus;

/** Business state of an AGV. Position between nodes, speed and route are motion data and stay in memory. */
public record AgvRecord(String id, String name, AgvStatus status, String nodeId, double battery, boolean enabled,
                        int version) {}
