package io.nexus.flowpilot.fleet;

import io.nexus.contracts.state.AgvStatus;
import io.nexus.flowpilot.store.AgvRecord;
import java.util.ArrayList;
import java.util.List;

/**
 * One AGV with its motion data. Business state (status, node, battery, enabled) is persisted through the
 * FleetStore when it changes; position, speed and route stay in memory.
 */
final class Vehicle {
  final String id;
  final String name;
  AgvStatus status;
  /** Last node reached; the vehicle is there or on the edge to route[0]. */
  String nodeId;
  double x;
  double z;
  /** Unit vector of the direction of travel. Kept instead of an angle: no trigonometry in the logic (Spec 2a). */
  double dirX = 1;
  double dirZ;
  double speed;
  double battery;
  boolean enabled;
  String transportId;
  /** Nodes still to visit, next one first. */
  final List<String> route = new ArrayList<>();
  /** Node the current route leads to; null when the vehicle has nowhere to go. */
  String goal;
  /** Remaining loading or unloading time. */
  long timerMs;
  String chargerId;
  double chargeTarget = AgvPhysics.RESUME_FROM_CHARGE;
  /** Set by sendToCharge for a busy vehicle: charge once the current transport is done. */
  boolean chargeRequested;
  String blockedBy;
  /** Warned once that the finished goods store is full for the current unloading. */
  boolean unloadWarned;
  /** Reported once that the current goal cannot be reached. */
  boolean routeBlockedReported;
  String faultTypeId;
  long faultUntilMs;
  /** Transport whose load is still on board after this vehicle failed; a replacement picks it up here. */
  String heldLoad;
  /** For a replacement: the failed vehicle it picks the load up from. Close behind it counts as arrived. */
  String rescueFrom;
  /** Message of the last failed step, so a vehicle stuck on the same problem is reported once. */
  String lastFailure;
  /** What the FleetStore holds; compared to decide whether to write. */
  AgvRecord saved;

  Vehicle(AgvRecord record, double x, double z) {
    this.id = record.id();
    this.name = record.name();
    this.status = record.status();
    this.nodeId = record.nodeId();
    this.battery = record.battery();
    this.enabled = record.enabled();
    this.saved = record;
    this.x = x;
    this.z = z;
  }

  boolean driving() {
    return status == AgvStatus.TO_PICKUP || status == AgvStatus.TO_DROP || status == AgvStatus.TO_CHARGE;
  }

  /** The record to persist now: battery rounded to 0.1 so motion alone does not cause writes. */
  AgvRecord record() {
    return new AgvRecord(id, name, status, nodeId, Math.round(battery * 10) / 10.0, enabled, saved.version());
  }
}
