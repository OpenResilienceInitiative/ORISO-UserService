package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.service.TenantCaseHandoverPolicyReadClient;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CalendarPolicyResolverTest {

  private static final long TENANT = 7L;

  @Mock TenantCaseHandoverPolicyReadClient tenantPolicies;
  @Mock TenantService tenantService;

  private final MovableClock clock = new MovableClock(Instant.parse("2026-11-14T10:00:00Z"));
  private CalendarPolicyResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new CalendarPolicyResolver(tenantPolicies, tenantService, clock);
    ReflectionTestUtils.setField(resolver, "multitenancy", true);
  }

  @Test
  void aSlowFailedRead_Should_BeRememberedFromItsEnd_NotFromItsStart() {
    when(tenantPolicies.getTenantPermissionPoliciesRaw(TENANT))
        .thenAnswer(
            call -> {
              clock.advance(Duration.ofSeconds(11));
              throw new IllegalStateException("synthetic outage");
            });

    assertThat(resolver.resolve(TENANT)).isEqualTo(CalendarPolicy.CLOSED);
    assertThat(resolver.resolve(TENANT)).isEqualTo(CalendarPolicy.CLOSED);

    verify(tenantPolicies, times(1)).getTenantPermissionPoliciesRaw(TENANT);
    clock.advance(Duration.ofSeconds(10));
    resolver.resolve(TENANT);
    verify(tenantPolicies, times(2)).getTenantPermissionPoliciesRaw(TENANT);
  }

  @Test
  void aSlowSingleTenantRead_Should_BeRememberedFromItsEnd() {
    ReflectionTestUtils.setField(resolver, "multitenancy", false);
    when(tenantService.getSingleTenancyTenantDataFresh())
        .thenAnswer(
            call -> {
              clock.advance(Duration.ofSeconds(61));
              return new RestrictedTenantDTO().id(TENANT);
            });
    when(tenantPolicies.getTenantPermissionPoliciesRaw(TENANT)).thenReturn(Map.of());

    resolver.resolve(null);
    resolver.resolve(null);

    verify(tenantService, times(1)).getSingleTenancyTenantDataFresh();
  }

  @Test
  void concurrentCallers_Should_ShareOneSingleTenantRead() throws Exception {
    ReflectionTestUtils.setField(resolver, "multitenancy", false);
    var reads = new AtomicInteger();
    var firstReadStarted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(tenantService.getSingleTenancyTenantDataFresh())
        .thenAnswer(
            call -> {
              reads.incrementAndGet();
              firstReadStarted.countDown();
              release.await(5, TimeUnit.SECONDS);
              return new RestrictedTenantDTO().id(TENANT);
            });
    when(tenantPolicies.getTenantPermissionPoliciesRaw(anyLong())).thenReturn(Map.of());

    var first = CompletableFuture.runAsync(() -> resolver.resolve(null));
    assertThat(firstReadStarted.await(5, TimeUnit.SECONDS)).isTrue();
    var waiter = new Thread(() -> resolver.resolve(null));
    waiter.start();
    // The second caller either waits for the first read or starts a read of its own.
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (waiter.getState() != Thread.State.BLOCKED
        && reads.get() < 2
        && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    release.countDown();
    first.get(5, TimeUnit.SECONDS);
    waiter.join(5_000);

    assertThat(reads.get()).isEqualTo(1);
  }

  /** A clock the test moves forward. */
  private static final class MovableClock extends Clock {

    private volatile Instant now;

    MovableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
