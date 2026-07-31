package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdReservation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * TEN-INV-U3 (#889): invite creation orchestrates the authoritative tenant-ID reservations.
 *
 * <p>TenantService/AgencyService are mocked at the client boundary with a realistic in-memory
 * reservation ledger (first reservation of an ID wins and returns 201-equivalent data, the loser
 * gets the 409-mapped {@link ConflictException}), while the invite persistence and the service
 * transaction run for real against the JPA layer.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(AccountInviteService.class)
class AccountInviteReservationOrchestrationIT {

  @Autowired private AccountInviteService service;
  @Autowired private AccountInviteRepository accountInviteRepository;

  @MockitoBean private AuthenticatedUser authenticatedUser;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private TenantIdAllocationClient tenantIdAllocationClient;
  @MockitoBean private AgencyIdAllocationClient agencyIdAllocationClient;

  // TEN-INV-U6 collaborators of the send path — not exercised by these creation-focused tests.
  @MockitoBean private InviteAcceptUrlBuilder inviteAcceptUrlBuilder;

  @MockitoBean
  private de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService
      inviteMailDispatchService;

  @MockitoBean private InviteEmailDeliveryFailureRecorder deliveryFailureRecorder;

  /** In-memory stand-in for the TenantService reservation ledger (U1). */
  private final Set<Long> tenantIdLedger = ConcurrentHashMap.newKeySet();

  @BeforeEach
  void setUpRealisticTenantIdLedger() {
    when(authenticatedUser.getUserId()).thenReturn("admin-1");
    when(authenticatedUser.getUsername()).thenReturn("admin@example.org");

    // AUTO mode: the smallest currently free ID is reserved atomically (ledger insert wins).
    when(tenantIdAllocationClient.reserve(isNull()))
        .thenAnswer(
            invocation -> {
              for (long candidate = 1; ; candidate++) {
                if (tenantIdLedger.add(candidate)) {
                  return new TenantIdReservation(candidate, "token-" + candidate);
                }
              }
            });
    // MANUAL mode: exactly the requested ID is reserved, or the loser gets the mapped 409.
    when(tenantIdAllocationClient.reserve(anyLong()))
        .thenAnswer(
            invocation -> {
              long requested = invocation.getArgument(0);
              if (!tenantIdLedger.add(requested)) {
                throw new ConflictException(
                    "tenantId " + requested + " is already assigned or reserved");
              }
              return new TenantIdReservation(requested, "token-" + requested);
            });
    when(tenantIdAllocationClient.getAvailability(anyLong()))
        .thenAnswer(
            invocation ->
                tenantIdLedger.contains((long) invocation.getArgument(0))
                    ? IdAllocationStatus.RESERVED
                    : IdAllocationStatus.FREE);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              tenantIdLedger.remove((long) invocation.getArgument(0));
              return null;
            })
        .when(tenantIdAllocationClient)
        .release(anyLong());
  }

  @AfterEach
  void cleanUp() {
    accountInviteRepository.deleteAll();
    tenantIdLedger.clear();
  }

  @Test
  void parallelAutoInvites_Should_ReceiveDifferentTenantIds() throws Exception {
    List<AccountInvite> invites =
        runConcurrently(
            () -> createTenantAdminInvite(null, IdAllocationMode.AUTO, "auto-a@example.org"),
            () -> createTenantAdminInvite(null, IdAllocationMode.AUTO, "auto-b@example.org"));

    assertThat(invites).hasSize(2);
    assertThat(invites.get(0).getTenantId()).isNotEqualTo(invites.get(1).getTenantId());
    assertThat(invites.get(0).getTenantIdReservationToken())
        .isNotEqualTo(invites.get(1).getTenantIdReservationToken());
    assertThat(accountInviteRepository.count()).isEqualTo(2);
    assertThat(tenantIdLedger).contains(invites.get(0).getTenantId(), invites.get(1).getTenantId());
  }

  @Test
  void parallelManualInvitesForSameId_Should_LetExactlyOneSucceed() throws Exception {
    List<Object> outcomes =
        runConcurrentlyCollectingErrors(
            () -> createTenantAdminInvite(21L, IdAllocationMode.MANUAL, "manual-a@example.org"),
            () -> createTenantAdminInvite(21L, IdAllocationMode.MANUAL, "manual-b@example.org"));

    List<AccountInvite> successes =
        outcomes.stream()
            .filter(AccountInvite.class::isInstance)
            .map(AccountInvite.class::cast)
            .toList();
    List<Object> conflicts = outcomes.stream().filter(ConflictException.class::isInstance).toList();

    assertThat(successes).hasSize(1);
    assertThat(conflicts).hasSize(1);
    assertThat(successes.get(0).getTenantId()).isEqualTo(21L);
    assertThat(accountInviteRepository.count()).isEqualTo(1);
    // The winner's reservation is still held — the loser's conflict released nothing.
    assertThat(tenantIdLedger).containsExactly(21L);
  }

  @Test
  void failedCreation_Should_LeaveNoReservationBehind() {
    // The re-validation directly before saving loses the race: the ID is meanwhile ASSIGNED.
    when(tenantIdAllocationClient.getAvailability(anyLong()))
        .thenReturn(IdAllocationStatus.ASSIGNED);

    assertThatThrownBy(
            () -> createTenantAdminInvite(21L, IdAllocationMode.MANUAL, "owner@example.org"))
        .isInstanceOf(ConflictException.class);

    verify(tenantIdAllocationClient).release(21L);
    assertThat(accountInviteRepository.count()).isZero();
    assertThat(tenantIdLedger).isEmpty();
  }

  private AccountInvite createTenantAdminInvite(
      Long tenantId, IdAllocationMode mode, String email) {
    return service.createInvite(
        new CreateAccountInviteCommand(
            AccountInviteTargetRole.TENANT_ADMIN,
            tenantId,
            email,
            null,
            null,
            null,
            null,
            null,
            mode,
            null));
  }

  private List<AccountInvite> runConcurrently(
      Callable<AccountInvite> first, Callable<AccountInvite> second) throws Exception {
    List<Object> outcomes = runConcurrentlyCollectingErrors(first, second);
    List<AccountInvite> invites = new ArrayList<>();
    for (Object outcome : outcomes) {
      if (outcome instanceof Exception exception) {
        throw exception;
      }
      invites.add((AccountInvite) outcome);
    }
    return invites;
  }

  private List<Object> runConcurrentlyCollectingErrors(
      Callable<AccountInvite> first, Callable<AccountInvite> second) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch startTogether = new CountDownLatch(1);
      List<Future<Object>> futures =
          List.of(first, second).stream()
              .map(
                  callable ->
                      executor.submit(
                          (Callable<Object>)
                              () -> {
                                startTogether.await(5, TimeUnit.SECONDS);
                                try {
                                  return callable.call();
                                } catch (Exception exception) {
                                  return exception;
                                }
                              }))
              .toList();
      startTogether.countDown();
      List<Object> outcomes = new ArrayList<>();
      for (Future<Object> future : futures) {
        outcomes.add(future.get(30, TimeUnit.SECONDS));
      }
      return outcomes;
    } finally {
      executor.shutdownNow();
    }
  }
}
