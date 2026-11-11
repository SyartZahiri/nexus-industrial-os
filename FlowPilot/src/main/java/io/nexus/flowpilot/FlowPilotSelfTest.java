package io.nexus.flowpilot;

/**
 * Self-test of the module for the browser build: platform/web runs it on the JVM and, compiled by TeaVM,
 * in Node, and requires identical output. Replaced by the module's real self-test in its plan.
 */
public final class FlowPilotSelfTest {
  private FlowPilotSelfTest() {}

  public static String run(long seed) {
    return "bereit";
  }
}
