package io.nexus.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.sim.SeededRandom;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebRuntimeTest {
  @Test
  void selfTestDeliversInFifoOrderRetriesAndIsDeterministic() throws Exception {
    String out = WebRuntime.selfTest(4711);
    List<String> lines = out.lines().toList();

    var r = new SeededRandom(4711);
    assertThat(lines).startsWith(
        "1-0 order.created a-follow",          // b-flaky fails its first attempt here
        "1-1 scenario.finished a-follow",      // published by a-follow, delivered after 1-0 reached everyone
        "1-0 order.created b-flaky",           // retried; b-flaky never sees the effect before the cause
        "1-1 scenario.finished b-flaky",       // was parked behind 1-0
        "random=" + Long.toHexString(r.nextLong()) + "," + Long.toHexString(r.nextLong()) + ","
            + Long.toHexString(r.nextLong()));
    // each core module contributes exactly one line; its content is the module's own self-test
    assertThat(lines).filteredOn(l -> l.startsWith("flowpilot=")).hasSize(1);
    assertThat(lines).filteredOn(l -> l.startsWith("smartfactory=")).hasSize(1);
    assertThat(WebRuntime.selfTest(4711)).as("same seed, same output").isEqualTo(out);
    assertThat(WebRuntime.selfTest(4712)).isNotEqualTo(out);

    // smoke.mjs compares the JavaScript build against exactly this text
    Files.writeString(Path.of("target", "selftest-jvm.txt"), out);
  }
}
