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
          action.run();
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
