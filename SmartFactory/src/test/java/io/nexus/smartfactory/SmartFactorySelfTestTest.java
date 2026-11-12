package io.nexus.smartfactory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SmartFactorySelfTestTest {
  @Test
  void sameSeedSameOutput() {
    String out = SmartFactorySelfTest.run(4711);
    assertThat(SmartFactorySelfTest.run(4711)).isEqualTo(out);
    assertThat(SmartFactorySelfTest.run(4712)).isNotEqualTo(out);
  }

  @Test
  void everyOrderEndsCompleted() {
    List<String> orders = SmartFactorySelfTest.run(4711).lines().filter(l -> l.startsWith("PO-")).toList();
    assertThat(orders).hasSize(4).allSatisfy(l ->
        assertThat(l).endsWith("CREATED>MATERIAL_PENDING>RELEASED>IN_PROGRESS>COMPLETED"));
  }

  @Test
  void theFaultAtTheMillingCenterReschedulesAtLeastOneOrder() {
    String out = SmartFactorySelfTest.run(4711);
    String line = out.lines().filter(l -> l.startsWith("rescheduled=")).findFirst().orElseThrow();
    assertThat(Integer.parseInt(line.substring("rescheduled=".length()))).isPositive();
    assertThat(out).contains("plan: ").contains("jobs: ").contains("kpis: ");
    assertThat(out.lines().filter(l -> l.startsWith("jobs: ")).findFirst().orElseThrow()).doesNotContain("CNC-01:PO-0003/1");
  }
}
