package io.nexus.flowpilot.fleet;

/** Vehicle values from FactoryTwin/src/sim/config.ts (AGV_PHYSICS, FAULT_TYPES). */
public final class AgvPhysics {
  private AgvPhysics() {}

  /** m/s */
  public static final double MAX_SPEED = 2.2;
  /** m/s² */
  public static final double ACCEL = 1.0;
  /** Braking is stronger than accelerating, as in the former engine. */
  public static final double BRAKE = 2.5 * ACCEL;
  /** With an empty battery a vehicle crawls on, so it does not block the lane. */
  public static final double CRAWL_SPEED = 0.3;
  public static final long LOAD_TIME_MS = 2200;
  /** % per metre driven */
  public static final double DRAIN_PER_METER = 0.022;
  /** % per second standing */
  public static final double DRAIN_IDLE = 0.004;
  /** % per second at a charger */
  public static final double CHARGE_RATE = 0.5;
  public static final double LOW_BATTERY = 25;
  public static final double MIN_FOR_TASK = 18;
  public static final double RESUME_FROM_CHARGE = 40;
  /** Target of a charge requested by the operator. */
  public static final double MANUAL_CHARGE_TARGET = 95;
  /** Battery kept in reserve on top of minimum and trip when dispatching. */
  public static final double TASK_RESERVE = 5;
  /** m, centre to centre, in the direction of travel */
  public static final double SAFETY_DISTANCE = 1.75;
  /** m; vehicles further to the side are in another lane */
  public static final double LANE_HALF_WIDTH = 0.6;
  /** Repair time of the AGV fault types NAV and COMM. */
  public static final long FAULT_REPAIR_MS = 60_000;
}
