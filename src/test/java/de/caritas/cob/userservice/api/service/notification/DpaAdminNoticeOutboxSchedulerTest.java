package de.caritas.cob.userservice.api.service.notification;

import static de.caritas.cob.userservice.api.service.notification.DpaAdminNoticeOutboxScheduler.PAGE_SIZE;
import static de.caritas.cob.userservice.api.service.notification.DpaAdminNoticeOutboxScheduler.TASK_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.service.notification.DpaAdminNoticeOutboxConsumer.Event;
import de.caritas.cob.userservice.api.service.notification.DpaAdminNoticeOutboxConsumer.VerifiedSignature;
import de.caritas.cob.userservice.api.service.notification.TenantDpaSignatureReadClient.PendingPage;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService.ClaimLease;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.DpaSignatureDTO;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DpaAdminNoticeOutboxSchedulerTest {

  private static final LocalDateTime LEASE_END = LocalDateTime.of(2026, 11, 19, 12, 0);
  private static final ClaimLease FIRST_LEASE = new ClaimLease(TASK_NAME, LEASE_END);
  private static final ClaimLease REPLACEMENT_LEASE =
      new ClaimLease(TASK_NAME, LEASE_END.plusMinutes(3));
  private static final String CURSOR_SQL =
      "select after_id from dpa_admin_notice_outbox_cursor where id = 1";

  @TempDir Path directory;

  private final DpaSignedNoticeService notices = mock(DpaSignedNoticeService.class);
  private final TenantDpaSignatureReadClient client = mock(TenantDpaSignatureReadClient.class);
  private final ScheduledTaskClaimService claims = mock(ScheduledTaskClaimService.class);
  private final AtomicBoolean inLeaseTransaction = new AtomicBoolean();
  private final AtomicReference<ClaimLease> activeLease = new AtomicReference<>(FIRST_LEASE);

  @BeforeEach
  void setUp() throws Exception {
    var liquibase = new SpringLiquibase();
    liquibase.setDataSource(dataSource());
    liquibase.setChangeLog(
        "classpath:db/changelog/changeset/20261119_dpa_admin_notice_outbox_cursor_1704/changeSet.xml");
    liquibase.afterPropertiesSet();

    when(client.isOutboxEnabled()).thenReturn(true);
    doAnswer(
            call -> {
              call.getArgument(0, Runnable.class).run();
              return null;
            })
        .when(notices)
        .submitAdminRecovery(any(Runnable.class));
    when(claims.tryClaimLease(eq(TASK_NAME), any()))
        .thenAnswer(call -> Optional.ofNullable(activeLease.get()));
    when(claims.runIfHeld(any(ClaimLease.class), any(Runnable.class)))
        .thenAnswer(
            call -> {
              if (!call.getArgument(0, ClaimLease.class).equals(activeLease.get())) {
                return false;
              }
              inLeaseTransaction.set(true);
              try {
                call.getArgument(1, Runnable.class).run();
                return true;
              } finally {
                inLeaseTransaction.set(false);
              }
            });
    when(claims.release(any(ClaimLease.class)))
        .thenAnswer(call -> activeLease.compareAndSet(call.getArgument(0, ClaimLease.class), null));
  }

  @Test
  void remainsUnregisteredUnlessBothOptionsAreExplicitlyEnabled() {
    var context =
        new ApplicationContextRunner()
            .withUserConfiguration(DpaAdminNoticeOutboxScheduler.class)
            .withBean(DpaSignedNoticeService.class, () -> notices)
            .withBean(TenantDpaSignatureReadClient.class, () -> client)
            .withBean(ScheduledTaskClaimService.class, () -> claims)
            .withBean(JdbcTemplate.class, this::jdbc);
    String read = "tenant.dpa.admin-notice-outbox.read-enabled=true";
    String dispatch = "tenant.dpa.admin-notice-outbox.dispatch-enabled=true";
    context.run(result -> assertThat(result).doesNotHaveBean(DpaAdminNoticeOutboxScheduler.class));
    context
        .withPropertyValues(read)
        .run(result -> assertThat(result).doesNotHaveBean(DpaAdminNoticeOutboxScheduler.class));
    context
        .withPropertyValues(dispatch)
        .run(result -> assertThat(result).doesNotHaveBean(DpaAdminNoticeOutboxScheduler.class));
    context
        .withPropertyValues(read, dispatch)
        .run(result -> assertThat(result).hasSingleBean(DpaAdminNoticeOutboxScheduler.class));
    verifyNoInteractions(claims);
  }

  @Test
  void resumesAnEmptyPageAcrossWorkerReplacementAndWrapsOnlyAfterTheLastPage() {
    when(client.readPending(0, PAGE_SIZE)).thenReturn(new PendingPage(List.of(), 9L));
    scheduler().retryPending();
    assertThat(cursor()).isEqualTo(9);

    var event = new Event(11, 20, 30);
    acceptOwnership(event);
    when(client.readPending(9, PAGE_SIZE)).thenReturn(new PendingPage(List.of(event), null));
    activeLease.set(REPLACEMENT_LEASE);
    scheduler().retryPending();

    verify(client).acknowledge(event);
    verify(notices, org.mockito.Mockito.times(2)).retryPendingExactAdminNotices(any());
    assertThat(cursor()).isZero();
  }

  @Test
  void failedPartialPageAdvancesOnlyToAttemptedEntryAndRevisitsHeldAndFailedEntries() {
    var held = new Event(2, 20, 30);
    var failed = new Event(7, 20, 31);
    var later = new Event(12, 20, 32);
    var heldSignature = signatureFor(held);
    when(client.readExact(held))
        .thenReturn(Optional.of(new VerifiedSignature(held, heldSignature)));
    when(notices.acceptExactAdminNotice(held, heldSignature)).thenReturn(false, true);
    var failedSignature = signatureFor(failed);
    when(client.readExact(failed))
        .thenThrow(new IllegalStateException("synthetic read failed"))
        .thenReturn(Optional.of(new VerifiedSignature(failed, failedSignature)));
    when(notices.acceptExactAdminNotice(failed, failedSignature)).thenReturn(true);
    acceptOwnership(later);
    when(client.readPending(0, PAGE_SIZE))
        .thenReturn(new PendingPage(List.of(held, failed, later), 15L));
    when(client.readPending(7, PAGE_SIZE)).thenReturn(new PendingPage(List.of(later), null));

    scheduler().retryPending();
    assertThat(cursor()).isEqualTo(7);
    verify(client, never()).readExact(later);
    verify(client, never()).acknowledge(held);
    verify(client, never()).acknowledge(failed);

    activeLease.set(REPLACEMENT_LEASE);
    scheduler().retryPending();
    assertThat(cursor()).isZero();
    verify(client).acknowledge(later);

    activeLease.set(new ClaimLease(TASK_NAME, LEASE_END.plusMinutes(6)));
    scheduler().retryPending();
    verify(client).acknowledge(held);
    verify(client).acknowledge(failed);
    assertThat(cursor()).isEqualTo(15);
  }

  @Test
  void leaseLostBeforeAckCannotAdvanceOrReleaseTheNewLease() {
    var event = new Event(2, 20, 30);
    var signature = signatureFor(event);
    when(client.readPending(0, PAGE_SIZE)).thenReturn(new PendingPage(List.of(event), 8L));
    when(client.readExact(event)).thenReturn(Optional.of(new VerifiedSignature(event, signature)));
    when(notices.acceptExactAdminNotice(event, signature))
        .thenAnswer(
            call -> {
              activeLease.set(REPLACEMENT_LEASE);
              return true;
            });

    scheduler().retryPending();

    verify(client, never()).acknowledge(event);
    verify(claims).release(FIRST_LEASE);
    assertThat(activeLease.get()).isEqualTo(REPLACEMENT_LEASE);
    assertThat(cursor()).isZero();
  }

  @Test
  void alreadyAuthorizedAckCanFinishAfterLeaseChangeWithoutCommittingStaleCursor() {
    var event = new Event(2, 20, 30);
    var signature = acceptOwnership(event);
    when(client.readPending(0, PAGE_SIZE)).thenReturn(new PendingPage(List.of(event), 8L));
    doAnswer(
            call -> {
              assertThat(inLeaseTransaction).isFalse();
              activeLease.set(REPLACEMENT_LEASE);
              return null;
            })
        .when(client)
        .acknowledge(event);

    scheduler().retryPending();

    var order = inOrder(notices, client);
    order.verify(notices).acceptExactAdminNotice(event, signature);
    order.verify(client).acknowledge(event);
    verify(claims).release(FIRST_LEASE);
    assertThat(activeLease.get()).isEqualTo(REPLACEMENT_LEASE);
    assertThat(cursor()).isZero();
  }

  private DpaSignatureDTO acceptOwnership(Event event) {
    var signature = signatureFor(event);
    when(client.readExact(event)).thenReturn(Optional.of(new VerifiedSignature(event, signature)));
    when(notices.acceptExactAdminNotice(event, signature)).thenReturn(true);
    return signature;
  }

  private DpaSignatureDTO signatureFor(Event event) {
    return new DpaSignatureDTO().tenantId(event.tenantId());
  }

  private DriverManagerDataSource dataSource() {
    return new DriverManagerDataSource("jdbc:h2:file:" + directory.resolve("outbox"), "sa", "");
  }

  private JdbcTemplate jdbc() {
    return new JdbcTemplate(dataSource());
  }

  private long cursor() {
    return jdbc().queryForObject(CURSOR_SQL, Long.class);
  }

  private DpaAdminNoticeOutboxScheduler scheduler() {
    return new DpaAdminNoticeOutboxScheduler(notices, client, claims, jdbc());
  }
}
