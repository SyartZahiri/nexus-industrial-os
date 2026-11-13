package io.nexus.plant;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.sim.SeededRandom;
import io.nexus.smartfactory.SmartFactory;
import io.nexus.smartfactory.master.SmartFactoryMasterData;
import io.nexus.smartfactory.orders.NewOrder;
import io.nexus.smartfactory.store.ProductionOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Customers placing orders by themselves: random product, quantity 3..10, mostly normal priority, due in 45..90 min. */
final class OrderGenerator {
  private final SeededRandom random;
  private final List<String> products = new ArrayList<>();
  private long nextOrderAtMs;

  OrderGenerator(SeededRandom random) {
    this.random = random;
    for (SmartFactoryMasterData.Product p : SmartFactoryMasterData.products()) products.add(p.id());
    products.sort(Comparator.naturalOrder());
  }

  void step(long nowMs, PlantRules rules, SmartFactory factory) {
    if (!rules.autoOrders() || nowMs < nextOrderAtMs) return;
    nextOrderAtMs = nowMs + rules.autoOrderIntervalMs();
    int open = 0;
    for (ProductionOrder o : factory.orders()) if (!o.status().terminal()) open++;
    if (open >= rules.maxOpenOrders()) return;
    String product = products.get(random.nextInt(products.size()));
    int quantity = 3 + random.nextInt(8);
    int p = random.nextInt(10);
    Priority priority = p < 7 ? Priority.NORMAL : p < 9 ? Priority.HOCH : Priority.EILIG;
    long dueInMs = (45 + random.nextInt(46)) * 60_000L;
    factory.createOrder(new NewOrder(product, quantity, priority, dueInMs));
  }
}
