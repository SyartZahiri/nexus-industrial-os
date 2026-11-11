package io.nexus.app.event;

import io.nexus.contracts.event.UnitOfWork;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** UnitOfWork on Spring transactions. */
@Component
class SpringUnitOfWork implements UnitOfWork {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SpringUnitOfWork.class);
  private final TransactionTemplate requiresNew;

  SpringUnitOfWork(PlatformTransactionManager txManager) {
    this.requiresNew = new TransactionTemplate(txManager);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public void afterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()
        && TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          // the publisher's work is committed; a delivery problem must not turn its request into an error
          try {
            action.run();
          } catch (RuntimeException | Error e) {
            log.error("Zustellung nach Commit fehlgeschlagen", e);
          }
        }
      });
    } else {
      action.run();
    }
  }

  @Override
  public void inNewTransaction(Runnable action) {
    requiresNew.executeWithoutResult(status -> action.run());
  }
}
