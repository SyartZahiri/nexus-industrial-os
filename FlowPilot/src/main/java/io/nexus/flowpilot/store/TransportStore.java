package io.nexus.flowpilot.store;

import java.util.List;
import java.util.Optional;

public interface TransportStore {
  /** Fails with DomainException CONFLICT if the request id is already taken. */
  void insert(TransportOrder t);

  /** Fails with DomainException CONFLICT if the stored version differs; stores and returns version + 1. */
  TransportOrder update(TransportOrder t);

  Optional<TransportOrder> byId(String id);

  Optional<TransportOrder> byRequest(String requestId);

  /** Not DELIVERED and not CANCELLED; ordered by priority (EILIG first), createdAtMs, id. */
  List<TransportOrder> open();

  /** Ordered by id. */
  List<TransportOrder> all();
}
