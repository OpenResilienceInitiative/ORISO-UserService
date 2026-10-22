package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCancellation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCancellation.Reason;
import de.caritas.cob.userservice.api.model.AccountInvite;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual web-style open EntityManager and independently committed physical SQL transfer. */
@TestPropertySource(properties = "TASK_IDENTITY_MODEL=FIVE_ACTORS")
class ProtectedInvitationSqlViewJpaIT extends ProtectedInvitationIssuanceJpaFixture {
  @Autowired EntityManagerFactory factory;
  @MockitoBean ProtectedInvitationCancellation cancellation;

  @Test
  void anOpenRequestEntityManagerCannotOverwriteAReplacementCommittedBeforeAcknowledgedCleanup()
      throws Exception {
    var old = service.createInvite(command());
    var held = old.getTenantIdReservationToken();
    old.setStatus(AccountInviteStatus.SUPERSEDED);
    old.setSupersededAt(LocalDateTime.now().minusDays(2).withNano(0));
    old.setExpiresAt(LocalDateTime.now().minusDays(1).withNano(0));
    old.setTokenHash(null);
    old.setActiveRecipientKey(null);
    invites.saveAndFlush(old);
    var replacement = service.createInvite(command());
    assertThat(held).isNotBlank();
    assertThat(replacement.getTenantIdReservationToken()).isEqualTo(held);
    when(cancellation.pendingInviteIds(100)).thenReturn(List.of(old.getId()));

    var ready = new CountDownLatch(1);
    var transfer = new CountDownLatch(1);
    var driverSession = new AtomicLong();
    var executor = Executors.newSingleThreadExecutor();
    // Reserve this actual connection before warming JPA: the two observations must use distinct
    // database sessions, not one connection wrapper or a mutation of the managed Java object.
    var driver =
        executor.submit(
            () -> {
              try (var connection = source.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement();
                    var session = statement.executeQuery("SELECT SESSION_ID()")) {
                  assertThat(session.next()).isTrue();
                  driverSession.set(session.getLong(1));
                }
                ready.countDown();
                assertThat(transfer.await(10, TimeUnit.SECONDS)).isTrue();
                try (var update =
                    connection.prepareStatement(
                        "UPDATE account_invite SET superseded_by_invite_id=?,tenant_id_reservation_token=NULL"
                            + " WHERE id=? AND status='SUPERSEDED'")) {
                  update.setLong(1, replacement.getId());
                  update.setLong(2, old.getId());
                  assertThat(update.executeUpdate()).isEqualTo(1);
                }
                connection.commit();
                return null;
              }
            });
    var osiv = factory.createEntityManager();
    boolean bound = false;
    try {
      assertThat(TransactionSynchronizationManager.hasResource(factory)).isFalse();
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      TransactionSynchronizationManager.bindResource(factory, new EntityManagerHolder(osiv));
      bound = true;
      var warm = osiv.find(AccountInvite.class, old.getId());
      assertThat(osiv.contains(warm)).isTrue();
      assertThat(warm.getSupersededByInviteId()).isNull();
      assertThat(warm.getTenantIdReservationToken()).isEqualTo(held);
      var requestSession = ((Number) osiv.createNativeQuery("SELECT SESSION_ID()").getSingleResult()).longValue();
      assertThat(driverSession.get()).isPositive().isNotEqualTo(requestSession);
      doAnswer(
              call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                transaction().executeWithoutResult(tx -> ((Runnable) call.getArgument(2)).run());
                transfer.countDown();
                driver.get(10, TimeUnit.SECONDS);
                // Prove actual stale L1 versus committed SQL before invoking the public cleanup.
                assertThat(warm.getSupersededByInviteId()).isNull();
                assertThat(warm.getTenantIdReservationToken()).isEqualTo(held);
                assertThat(
                        jdbc.queryForObject(
                            "SELECT superseded_by_invite_id FROM account_invite WHERE id=?",
                            Long.class, old.getId()))
                    .isEqualTo(replacement.getId());
                return null;
              })
          .when(cancellation)
          .cancel(eq(old.getId()), eq(Reason.SUPERSEDED), any());
      when(cancellation.withAcknowledged(eq(old.getId()), any()))
          .thenAnswer(
              call ->
                  transaction().execute(
                      tx -> {
                        assertThat(EntityManagerFactoryUtils.getTransactionalEntityManager(factory))
                            .isSameAs(osiv);
                        return ((Supplier<?>) call.getArgument(1)).get();
                      }));

      assertDoesNotThrow(service::expireElapsedInvites);

      assertThat(
              jdbc.queryForObject(
                  "SELECT superseded_by_invite_id FROM account_invite WHERE id=?",
                  Long.class, old.getId()))
          .as("Committed replacement must survive stale request EntityManager cleanup")
          .isEqualTo(replacement.getId());
      assertThat(
              jdbc.queryForObject(
                  "SELECT tenant_id_reservation_token FROM account_invite WHERE id=?",
                  String.class, old.getId()))
          .isNull();
      assertThat(
              jdbc.queryForObject(
                  "SELECT tenant_id_reservation_token FROM account_invite WHERE id=?",
                  String.class, replacement.getId()))
          .isEqualTo(held);
      assertThat(releases.count()).isZero();
      verify(cancellation).withAcknowledged(eq(old.getId()), any());
      verify(tenantIds, never()).release(17L);
      verifyNoInteractions(sender, approvals);
    } finally {
      transfer.countDown();
      if (bound) TransactionSynchronizationManager.unbindResource(factory);
      osiv.close();
      driver.cancel(true);
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private TransactionTemplate transaction() {
    var transaction = new TransactionTemplate(transactions);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transaction;
  }
}
