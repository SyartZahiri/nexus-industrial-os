package io.nexus.contracts.port;

import java.util.List;

/** Implemented by SmartFactory. Unknown products raise DomainException NOT_FOUND. */
public interface ProductCatalog {
  /** Material demand of an order, one entry per (routing step, material). */
  List<MaterialDemand> demandFor(String productId, int quantity);

  /** The material id under which finished units of the product are stocked. */
  String finishedMaterialId(String productId);

  record MaterialDemand(int stepSeq, String materialId, int quantity) {}
}
