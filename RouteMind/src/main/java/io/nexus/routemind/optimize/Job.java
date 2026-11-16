package io.nexus.routemind.optimize;

/** One delivery to plan: where, how heavy and big, and the customer's time window (simulation ms). */
public record Job(String id, int place, int weightKg, int pallets, long earliestMs, long latestMs, long serviceMs) {}
