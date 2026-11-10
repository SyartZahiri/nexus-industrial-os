package io.nexus.contracts.port;

import java.util.List;

/** Implemented by SmartFactory. */
public interface ProductCatalog {
  /** Material demand of an order, one entry per (routing step, material). Unknown product: IllegalArgumentException. */
  List<MaterialDemand> demandFor(String productId, int quantity);

  record MaterialDemand(int stepSeq, String materialId, int quantity) {}
}
