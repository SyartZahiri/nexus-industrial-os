package io.nexus.flowpilot.store;

/** Stock of one material at one storage location. */
public record StockLine(String materialId, String locationId, int onHand, int reserved) {
  public int available() {
    return onHand - reserved;
  }
}
