package io.nexus.smartfactory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The bus calls SmartFactory while holding its own monitor; SmartFactory publishes and so enters the bus.
 * Both sides must take the locks in the same order — bus first — or two threads deadlock on the server.
 */
class SmartFactoryLockTest {
  @Test
  void facadeTakesTheBusLock() throws Exception {
    var h = new Harness();
    var done = new CountDownLatch(1);
    synchronized (h.bus) {
      var caller = new Thread(() -> {
        h.factory.orders();
        done.countDown();
      });
      caller.start();
      assertThat(done.await(300, TimeUnit.MILLISECONDS)).as("waits while another thread holds the bus").isFalse();
    }
    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
  }
}
