package de.caritas.cob.userservice.api.workflow.calendarimport.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.CalendarImportExecutorConfig;
import de.caritas.cob.userservice.api.service.calendar.CalendarImportRun;
import de.caritas.cob.userservice.api.service.calendar.CalendarSyncService;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@ExtendWith(MockitoExtension.class)
class CalendarImportSchedulerTest {

  private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
  private static final Duration CLAIM = Duration.ofMinutes(10);
  private static final Duration BUDGET = Duration.ofMinutes(4);
  private static final int THREADS = 2;

  @Mock CalendarSyncService syncService;
  @Mock TenantContextProvider tenantContextProvider;
  @Mock ScheduledTaskClaimService claims;

  private final CountDownLatch release = new CountDownLatch(1);
  private ThreadPoolTaskExecutor executor;
  private CalendarImportScheduler scheduler;

  @BeforeEach
  void setUp() {
    executor = new CalendarImportExecutorConfig().calendarImportExecutor(THREADS);
    scheduler =
        new CalendarImportScheduler(
            syncService,
            tenantContextProvider,
            claims,
            executor,
            Clock.fixed(NOW, ZoneOffset.UTC),
            true,
            CLAIM,
            BUDGET,
            THREADS);
  }

  @AfterEach
  void tearDown() {
    release.countDown();
    executor.shutdown();
  }

  @Test
  void importBusyTimes_Should_Return_WithoutWaitingForTheFetches() throws Exception {
    var working = new CountDownLatch(THREADS);
    var threads = new ConcurrentLinkedQueue<String>();
    var run = blockingRun(working, threads);
    when(claims.tryClaim(anyString(), any())).thenReturn(true);
    when(syncService.startImportRun(NOW.plus(BUDGET))).thenReturn(run);

    // The fetches wait until the test releases them, so only a hand-over can return in time.
    assertTimeoutPreemptively(Duration.ofSeconds(2), scheduler::importBusyTimes);

    assertThat(working.await(5, TimeUnit.SECONDS)).as("every worker took up the run").isTrue();
    assertThat(threads).hasSize(THREADS).allMatch(name -> name.startsWith("calendar-import-"));
    verify(claims).tryClaim("calendar-busy-import", CLAIM);
    release.countDown();
    verify(tenantContextProvider, timeout(5_000).times(THREADS))
        .setTechnicalContextIfMultiTenancyIsEnabled();
  }

  @Test
  void importBusyTimes_Should_StartNothing_When_AnotherReplicaHoldsTheClaim() {
    when(claims.tryClaim(anyString(), any())).thenReturn(false);

    scheduler.importBusyTimes();

    verifyNoInteractions(syncService);
  }

  @Test
  void importBusyTimes_Should_GiveUpQuietly_When_ThePoolIsStillBusy() throws Exception {
    var working = new CountDownLatch(THREADS);
    var run = blockingRun(working, new ConcurrentLinkedQueue<>());
    when(claims.tryClaim(anyString(), any())).thenReturn(true);
    when(syncService.startImportRun(any())).thenReturn(run);
    scheduler.importBusyTimes();
    assertThat(working.await(5, TimeUnit.SECONDS)).isTrue();
    // The queue holds one more run; the one after it does not fit.
    scheduler.importBusyTimes();

    assertThatCode(scheduler::importBusyTimes).doesNotThrowAnyException();
    verify(syncService, times(3)).startImportRun(any());
  }

  private CalendarImportRun blockingRun(
      CountDownLatch working, ConcurrentLinkedQueue<String> threads) {
    var run = mock(CalendarImportRun.class);
    doAnswer(
            call -> {
              threads.add(Thread.currentThread().getName());
              working.countDown();
              release.await(10, TimeUnit.SECONDS);
              return null;
            })
        .when(run)
        .work();
    return run;
  }
}
