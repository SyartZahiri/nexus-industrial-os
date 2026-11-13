package io.nexus.flowpilot.material;

import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.event.EventPublisher;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.port.IdSource;
import io.nexus.contracts.port.MaterialService;
import io.nexus.contracts.sim.SimTime;
import io.nexus.flowpilot.master.FlowPilotMasterData;
import io.nexus.flowpilot.store.Reservation;
import io.nexus.flowpilot.store.StockLine;
import io.nexus.flowpilot.store.StockStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stock and reservations. The port methods (reserve, release, availability) run in a unit of their own;
 * consume and receive run in the caller's unit, so they commit together with the transport step that needs them.
 */
public final class MaterialServiceImpl implements MaterialService {
  public static final String SOURCE = "flowpilot";

  private final StockStore stock;
  private final EventPublisher events;
  private final IdSource ids;
  private final UnitOfWork work;
  private final SimTime time;
  private final Map<String, FlowPilotMasterData.Material> materialsById = new HashMap<>();     // lookups only
  private final Map<String, FlowPilotMasterData.StorageLocation> locationsById = new HashMap<>(); // lookups only

  public MaterialServiceImpl(StockStore stock, EventPublisher events, IdSource ids, UnitOfWork work, SimTime time) {
    this.stock = stock;
    this.events = events;
    this.ids = ids;
    this.work = work;
    this.time = time;
    for (var m : FlowPilotMasterData.materials()) materialsById.put(m.id(), m);
    for (var l : FlowPilotMasterData.locations()) locationsById.put(l.id(), l);
  }

  @Override
  public ReservationResult reserve(ReservationRequest request) {
    validate(request);
    ReservationResult[] result = new ReservationResult[1];
    work.inNewTransaction(() -> result[0] = reserveLocked(request));
    if (result[0] instanceof ReservationResult.Shortage s) {
      // nothing was written; the report has a unit of its own
      work.inNewTransaction(() -> events.publish(EventTypes.MATERIAL_SHORTAGE, SOURCE, Severity.WARNING,
          request.orderId(), new Payloads.MaterialShortage(request.orderId(), s.materialId(), s.requested(),
              s.available())));
    }
    return result[0];
  }

  private ReservationResult reserveLocked(ReservationRequest request) {
    List<Reservation> existing = stock.reservationsOfRequest(request.requestId());
    if (!existing.isEmpty()) return success(request.requestId(), existing);   // idempotent
    List<Line> lines = new ArrayList<>(request.lines());
    lines.sort(Comparator.comparing(Line::materialId));       // fixed lock order: no deadlock between requests
    List<StockLine> chosen = new ArrayList<>();
    for (Line line : lines) {
      StockLine best = null;                                   // one location must cover the line on its own
      for (StockLine l : stock.lockMaterial(line.materialId())) {
        if (best == null || l.available() > best.available()) best = l;
      }
      int available = best == null ? 0 : best.available();
      if (available < line.quantity()) {
        return new ReservationResult.Shortage(line.materialId(), line.quantity(), available); // nothing written
      }
      chosen.add(best);
    }
    List<Reserved> made = new ArrayList<>();
    List<String> reservationIds = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {                   // ids only once every line is covered
      Line line = lines.get(i);
      StockLine l = chosen.get(i);
      var r = new Reserved(ids.next("RES"), line.materialId(), l.locationId(), line.quantity());
      var changed = new StockLine(l.materialId(), l.locationId(), l.onHand(), l.reserved() + r.quantity());
      stock.saveLine(changed);
      stock.saveReservation(new Reservation(r.reservationId(), request.requestId(), request.orderId(),
          r.materialId(), r.locationId(), r.quantity(), Reservation.Status.ACTIVE, time.nowMs()));
      stockChanged(changed, request.orderId());
      made.add(r);
      reservationIds.add(r.reservationId());
    }
    events.publish(EventTypes.MATERIAL_RESERVED, SOURCE, Severity.INFO, request.orderId(),
        new Payloads.MaterialReserved(request.orderId(), request.requestId(), List.copyOf(reservationIds)));
    return new ReservationResult.Success(request.requestId(), List.copyOf(made));
  }

  private static ReservationResult success(String requestId, List<Reservation> existing) {
    List<Reserved> out = new ArrayList<>();
    for (Reservation r : existing) out.add(new Reserved(r.id(), r.materialId(), r.locationId(), r.quantity()));
    return new ReservationResult.Success(requestId, List.copyOf(out));
  }

  private void validate(ReservationRequest request) {
    if (request == null || blank(request.requestId()) || blank(request.orderId())) {
      throw DomainException.invalid("Reservierung braucht requestId und orderId");
    }
    if (request.lines() == null || request.lines().isEmpty()) {
      throw DomainException.invalid("Reservierung ohne Positionen");
    }
    Set<String> seen = new HashSet<>();
    for (Line line : request.lines()) {
      material(line.materialId());
      if (line.quantity() <= 0) throw DomainException.invalid("Menge muss positiv sein: " + line.materialId());
      if (!seen.add(line.materialId())) {
        throw DomainException.invalid("Material doppelt in der Anfrage: " + line.materialId());
      }
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private FlowPilotMasterData.Material material(String id) {
    var m = id == null ? null : materialsById.get(id);
    if (m == null) throw DomainException.notFound("Material", id);
    return m;
  }

  private FlowPilotMasterData.StorageLocation location(String id) {
    var l = id == null ? null : locationsById.get(id);
    if (l == null) throw DomainException.notFound("Lagerplatz", id);
    return l;
  }

  @Override
  public void release(String requestId) {
    work.inNewTransaction(() -> {
      List<String> released = new ArrayList<>();
      String orderId = null;
      for (Reservation r : stock.reservationsOfRequest(requestId)) {
        if (r.status() != Reservation.Status.ACTIVE) continue;
        StockLine l = lineOf(r.materialId(), r.locationId());
        var changed = new StockLine(l.materialId(), l.locationId(), l.onHand(), l.reserved() - r.quantity());
        stock.saveLine(changed);
        stock.saveReservation(r.withStatus(Reservation.Status.RELEASED));
        stockChanged(changed, r.orderId());
        released.add(r.id());
        orderId = r.orderId();
      }
      if (!released.isEmpty()) {
        events.publish(EventTypes.MATERIAL_RELEASED, SOURCE, Severity.INFO, orderId,
            new Payloads.MaterialReserved(orderId, requestId, List.copyOf(released)));
      }
    });
  }

  @Override
  public List<Availability> availability(String materialId) {
    material(materialId);
    List<Availability> out = new ArrayList<>();
    for (StockLine l : stock.lines()) {
      if (l.materialId().equals(materialId)) {
        out.add(new Availability(l.materialId(), l.locationId(), l.onHand(), l.reserved()));
      }
    }
    return out;
  }

  /** True if the reservation was given back (released) and can no longer be consumed. */
  public boolean released(String reservationId) {
    return stock.reservation(reservationId).map(r -> r.status() == Reservation.Status.RELEASED).orElse(false);
  }

  /**
   * Takes reserved stock out at pickup: ACTIVE → CONSUMED, on hand and reserved both drop.
   * An already consumed reservation is a no-op. Runs in the caller's unit of work.
   */
  public void consume(String reservationId) {
    Reservation r = stock.reservation(reservationId)
        .orElseThrow(() -> DomainException.notFound("Reservierung", reservationId));
    if (r.status() == Reservation.Status.CONSUMED) return;
    if (r.status() != Reservation.Status.ACTIVE) {
      throw DomainException.conflict("Reservierung " + reservationId + " ist bereits freigegeben");
    }
    StockLine l = lineOf(r.materialId(), r.locationId());
    var changed = new StockLine(l.materialId(), l.locationId(), l.onHand() - r.quantity(), l.reserved() - r.quantity());
    stock.saveLine(changed);
    stock.saveReservation(r.withStatus(Reservation.Status.CONSUMED));
    stockChanged(changed, r.orderId());
  }

  /**
   * Goods receipt. The material must belong to the zone of the location (raw material and kits to RAW,
   * finished goods to FINISHED). Checked before anything is written. Runs in the caller's unit of work.
   *
   * @param correlationId the production order the goods belong to, may be null
   */
  public void receive(String materialId, String locationId, int quantity, String correlationId) {
    var material = material(materialId);
    var location = location(locationId);
    if (quantity <= 0) throw DomainException.invalid("Menge muss positiv sein");
    String zone = material.kind().equals("FINISHED") ? "FINISHED" : "RAW";
    if (!zone.equals(location.zone())) {
      throw DomainException.invalid("Material " + materialId + " gehört nicht auf Lagerplatz " + locationId);
    }
    if (freeRoom(locationId) < quantity) {
      throw DomainException.conflict("Lagerplatz " + locationId + " voll");
    }
    StockLine before = null;
    for (StockLine l : stock.lockMaterial(materialId)) if (l.locationId().equals(locationId)) before = l;
    var changed = before == null
        ? new StockLine(materialId, locationId, quantity, 0)
        : new StockLine(materialId, locationId, before.onHand() + quantity, before.reserved());
    stock.saveLine(changed);
    stockChanged(changed, correlationId);
  }

  public void receive(String materialId, String locationId, int quantity) {
    receive(materialId, locationId, quantity, null);
  }

  /**
   * Goods issue, e.g. shipping finished goods. Only unreserved stock can leave. Runs in the caller's unit of work.
   */
  public void issue(String materialId, String locationId, int quantity) {
    material(materialId);
    location(locationId);
    if (quantity <= 0) throw DomainException.invalid("Menge muss positiv sein");
    StockLine l = lineOf(materialId, locationId);
    if (l.available() < quantity) {
      throw DomainException.conflict("Auf " + locationId + " sind nur " + l.available() + " " + materialId + " frei");
    }
    var changed = new StockLine(materialId, locationId, l.onHand() - quantity, l.reserved());
    stock.saveLine(changed);
    stockChanged(changed, null);
  }

  /** Capacity of the location minus everything on hand there. */
  public int freeRoom(String locationId) {
    int used = 0;
    for (StockLine l : stock.lines()) if (l.locationId().equals(locationId)) used += l.onHand();
    return location(locationId).capacity() - used;
  }

  private StockLine lineOf(String materialId, String locationId) {
    for (StockLine l : stock.lockMaterial(materialId)) if (l.locationId().equals(locationId)) return l;
    throw DomainException.notFound("Bestand", materialId + "@" + locationId);
  }

  private void stockChanged(StockLine l, String correlationId) {
    events.publish(EventTypes.STOCK_CHANGED, SOURCE, Severity.INFO, correlationId,
        new Payloads.StockChanged(l.materialId(), l.locationId(), l.onHand(), l.reserved()));
  }
}
