package io.nexus.routemind.master;

import io.nexus.contracts.DomainException;
import java.util.List;

/** Fleet and freight of RouteMind. Prices in CHF. */
public final class RouteMindMasterData {
  public static final long MINUTE = 60_000;
  public static final long HOUR = 60 * MINUTE;
  public static final long DAY = 24 * HOUR;
  /** The delivery shift ends at 22:00; the simulation day starts at 06:00 (time 0). */
  public static final long SHIFT_END = 16 * HOUR;

  public record VehicleSeed(String id, String name, int maxKg, int maxPallets, double costPerKm, double costPerHour) {}

  public record Freight(String productId, int kgPerUnit, int unitsPerPallet) {}

  private static final List<VehicleSeed> VEHICLES = List.of(
      new VehicleSeed("LKW-1", "LKW 7,5 t", 3000, 15, 1.40, 75),
      new VehicleSeed("LKW-2", "LKW 7,5 t", 3000, 15, 1.40, 75),
      new VehicleSeed("LW-1", "Lieferwagen", 1000, 4, 0.70, 60),
      new VehicleSeed("LW-2", "Lieferwagen", 1000, 4, 0.70, 60));

  private static final List<Freight> FREIGHT = List.of(
      new Freight("GH200", 18, 8), new Freight("AW50", 9, 20), new Freight("FP12", 4, 40), new Freight("MT7", 12, 12));

  private RouteMindMasterData() {}

  /** Ordered by id. */
  public static List<VehicleSeed> vehicles() {
    return VEHICLES;
  }

  public static VehicleSeed vehicle(String id) {
    for (VehicleSeed v : VEHICLES) if (v.id().equals(id)) return v;
    throw DomainException.notFound("Lieferfahrzeug", id);
  }

  public static Freight freight(String productId) {
    for (Freight f : FREIGHT) if (f.productId().equals(productId)) return f;
    throw DomainException.notFound("Produkt", productId);
  }

  public static int pallets(Freight f, int quantity) {
    return (quantity + f.unitsPerPallet() - 1) / f.unitsPerPallet();
  }
}
