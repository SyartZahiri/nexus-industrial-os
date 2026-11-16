package io.nexus.routemind.optimize;

/** One available vehicle; it must be back at the depot by endMs (end of its shift). */
public record Truck(String id, int maxKg, int maxPallets, double costPerKm, double costPerHour, long endMs) {}
