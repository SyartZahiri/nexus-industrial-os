package io.nexus.smartfactory.testkit;

import io.nexus.contracts.port.MaterialService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MaterialService stand-in for tests and the browser self-test, until FlowPilot is wired in (M1-D).
 * One storage location per material; all-or-nothing, idempotent on successful request ids like FlowPilot.
 * Raw materials start with 1000 in RAW-A, kits with 1000 in RAW-B.
 */
public final class FakeMaterialService implements MaterialService {
  private static final class Stock {
    String locationId;
    int onHand;
    int reserved;
  }

  private final Map<String, Stock> stock = new LinkedHashMap<>();
  private final Map<String, ReservationResult.Success> reservations = new LinkedHashMap<>();
  private final List<ReservationRequest> requests = new ArrayList<>();
  private final List<String> released = new ArrayList<>();

  public FakeMaterialService() {
    for (String m : new String[] {"RM-AW50", "RM-FP12", "RM-GH200", "RM-MT7"}) setStock(m, "RAW-A", 1000);
    for (String m : new String[] {"KIT-GH200", "KIT-MT7"}) setStock(m, "RAW-B", 1000);
  }

  /** Sets the stock on hand; reservations made so far stay. */
  public void setStock(String materialId, String locationId, int onHand) {
    Stock s = stock.computeIfAbsent(materialId, k -> new Stock());
    s.locationId = locationId;
    s.onHand = onHand;
  }

  @Override
  public ReservationResult reserve(ReservationRequest request) {
    requests.add(request);
    var existing = reservations.get(request.requestId());
    if (existing != null) return existing;
    for (Line line : request.lines()) {
      Stock s = stock.get(line.materialId());
      int available = s == null ? 0 : s.onHand - s.reserved;
      if (available < line.quantity()) return new ReservationResult.Shortage(line.materialId(), line.quantity(), available);
    }
    List<Reserved> made = new ArrayList<>();
    for (Line line : request.lines()) {
      Stock s = stock.get(line.materialId());
      s.reserved += line.quantity();
      made.add(new Reserved(request.requestId() + "/" + line.materialId(), line.materialId(), s.locationId, line.quantity()));
    }
    var success = new ReservationResult.Success(request.requestId(), made);
    reservations.put(request.requestId(), success);
    return success;
  }

  @Override
  public void release(String requestId) {
    released.add(requestId);
    var success = reservations.remove(requestId);
    if (success == null) return;
    for (Reserved r : success.reservations()) stock.get(r.materialId()).reserved -= r.quantity();
  }

  @Override
  public List<Availability> availability(String materialId) {
    Stock s = stock.get(materialId);
    return s == null ? List.of() : List.of(new Availability(materialId, s.locationId, s.onHand, s.reserved));
  }

  /** Every reserve call in order, repeated ones included. */
  public List<ReservationRequest> requests() {
    return List.copyOf(requests);
  }

  /** Request ids passed to release, in order. */
  public List<String> released() {
    return List.copyOf(released);
  }
}
