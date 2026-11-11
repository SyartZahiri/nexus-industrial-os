package io.nexus.smartfactory.catalog;

import io.nexus.contracts.DomainException;
import io.nexus.contracts.port.ProductCatalog;
import io.nexus.smartfactory.master.SmartFactoryMasterData;
import io.nexus.smartfactory.master.SmartFactoryMasterData.BomItem;
import io.nexus.smartfactory.master.SmartFactoryMasterData.RoutingStep;
import java.util.ArrayList;
import java.util.List;

/** Products, routings and bills of material, read from the master data. */
public final class Catalog implements ProductCatalog {
  @Override
  public List<MaterialDemand> demandFor(String productId, int quantity) {
    product(productId);
    if (quantity <= 0) throw DomainException.invalid("Menge muss positiv sein: " + quantity);
    List<MaterialDemand> demand = new ArrayList<>();
    for (BomItem b : SmartFactoryMasterData.bom()) {
      if (b.productId().equals(productId)) demand.add(new MaterialDemand(b.seq(), b.materialId(), b.quantity() * quantity));
    }
    return demand;
  }

  @Override
  public String finishedMaterialId(String productId) {
    return product(productId).finishedMaterialId();
  }

  /** Ordered by seq. */
  public List<RoutingStep> routing(String productId) {
    product(productId);
    List<RoutingStep> steps = new ArrayList<>();
    for (RoutingStep s : SmartFactoryMasterData.routing()) if (s.productId().equals(productId)) steps.add(s);
    return steps;
  }

  public RoutingStep step(String productId, int seq) {
    for (RoutingStep s : routing(productId)) if (s.seq() == seq) return s;
    throw DomainException.notFound("Arbeitsschritt", productId + "/" + seq);
  }

  public SmartFactoryMasterData.Product product(String productId) {
    for (var p : SmartFactoryMasterData.products()) if (p.id().equals(productId)) return p;
    throw DomainException.notFound("Produkt", productId);
  }
}
