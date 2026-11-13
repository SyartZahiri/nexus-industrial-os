package io.nexus.smartfactory.analytics;

import io.nexus.contracts.state.MachineStatus;
import java.util.Map;

/**
 * Key figures of a time window, computed from stored state changes.
 * machines: TreeMap by machine id; timeInStatusMs: EnumMap with every status — stable order in both runtimes.
 *
 * @param bottleneckMachineId highest utilization, ties by id; null if no machine ran
 */
public record Kpis(long fromMs, long toMs, double throughputPerHour, double avgLeadTimeS, double p90LeadTimeS,
                   double onTimeRate, Map<String, MachineKpi> machines, String bottleneckMachineId) {
  public record MachineKpi(double utilization, Map<MachineStatus, Long> timeInStatusMs,
                           double availability, double performance, double quality, double oee) {}
}
