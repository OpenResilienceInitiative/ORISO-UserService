package de.caritas.cob.userservice.api.service.guestjoin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.GuestJoinTarget;
import de.caritas.cob.userservice.api.port.out.*;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(properties = "spring.sql.init.mode=never")
@ActiveProfiles("testing")
@Import({
  GuestJoinAttemptStore.class,
  GuestJoinProvisioner.class,
  GuestJoinEligibility.class,
  de.caritas.cob.userservice.api.service.identity.GuestIdentityCatalog.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GuestJoinProvisionerTest {
  private static final String NAME = "biene_rayan_1234";
  private static final GuestJoinCapability KEY =
      GuestJoinCapability.parse(
          Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
  @Autowired GuestJoinAttemptStore store;
  @Autowired GuestJoinProvisioner provisioner;
  @Autowired GuestJoinAttemptRepository attempts;
  @Autowired AgencyInviteLinkRepository invites;
  @MockitoBean GuestIdentityAccount identity;
  @MockitoBean GuestChatIdentity matrix;
  private AgencyInviteLink invite;

  @BeforeEach
  void prepare() {
    invite =
        invites.saveAndFlush(
            AgencyInviteLink.builder()
                .token("local-proof-invite")
                .createdByUserId("local-test-admin")
                .tenantId(7L)
                .topicId(9L)
                .consultingTypeId(1)
                .linkKind("TENANT")
                .chatType("LIVE_CHAT")
                .anonymity("FULL")
                .status("ACTIVE")
                .createDate(LocalDateTime.now())
                .build());
    store.prepare(
        KEY,
        new GuestJoinTarget(invite.getId(), 7L, 9L, 1),
        NAME,
        "bee.svg",
        true,
        LocalDateTime.now().plusHours(1));
    when(identity.findOwned(anyString(), anyLong(), anyString())).thenReturn(Optional.empty());
    when(identity.createOnly(anyString(), anyString(), anyLong(), anyString()))
        .thenReturn("owned-id");
    when(matrix.ensureOwned(anyString(), anyString())).thenReturn("@" + NAME + ":matrix.example");
    when(matrix.createOnly(anyString(), anyString())).thenReturn("@" + NAME + ":matrix.example");
  }

  @AfterEach
  void cleanup() {
    attempts.deleteAll();
    invites.deleteAll();
  }

  @Test
  void confirmedInitialIdentityCollisionIsCommittedBeforeReportingAndDoesNotDispatchAgain() {
    when(identity.createOnly(anyString(), anyString(), anyLong(), anyString()))
        .thenThrow(
            new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
                "Existing unrelated account"));
    assertThatThrownBy(() -> provisioner.provision(KEY))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);
    var persisted = attempts.findByKeyHash(KEY.attemptHash()).orElseThrow();
    assertThat(persisted.getPhase().name()).isEqualTo("IDENTITY_COLLISION");
    assertThat(persisted.getIdentityUserId()).isNull();
    assertThat(persisted.getSessionId()).isNull();
    assertThatThrownBy(() -> provisioner.provision(KEY))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);
    verify(identity, times(1)).createOnly(eq(NAME), anyString(), eq(7L), anyString());
    verify(identity, never()).completeOwned(anyString(), anyString(), anyLong(), anyString());
    verify(identity, never()).deleteOwned(anyString(), anyString(), anyLong(), anyString());
    verifyNoInteractions(matrix);
  }

  @Test
  void uncertainIdentityWriteCannotBecomeAConfirmedCollisionOnRetry() {
    when(identity.createOnly(anyString(), anyString(), anyLong(), anyString()))
        .thenThrow(new ServiceUnavailableException("Lost provider response"))
        .thenThrow(
            new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
                "The first write may have succeeded"));
    assertThatThrownBy(() -> provisioner.provision(KEY))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThatThrownBy(() -> provisioner.provision(KEY))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasNoCause();
    assertThat(attempts.findByKeyHash(KEY.attemptHash()).orElseThrow().getPhase().name())
        .isEqualTo("IDENTITY_RECONCILING");
    verify(identity, times(2))
        .createOnly(eq(NAME), eq(KEY.identityPassword(NAME)), eq(7L), anyString());
    verifyNoInteractions(matrix);
  }

  @Test
  void uncertainChatWriteCannotAuthorizeReplacementOrRepeatIdentityCreation() {
    when(matrix.createOnly(anyString(), anyString()))
        .thenThrow(new ServiceUnavailableException("Lost chat response"));
    when(matrix.ensureOwned(anyString(), anyString()))
        .thenThrow(
            new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
                "The first chat write may have succeeded"));
    assertThatThrownBy(() -> provisioner.provision(KEY))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThatThrownBy(() -> provisioner.provision(KEY))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasNoCause();
    assertThat(attempts.findByKeyHash(KEY.attemptHash()).orElseThrow().getPhase().name())
        .isEqualTo("MATRIX_RECONCILING");
    verify(identity, times(1)).createOnly(eq(NAME), anyString(), eq(7L), anyString());
    verify(identity, times(1)).completeOwned(anyString(), eq(NAME), eq(7L), anyString());
    verify(matrix, times(1)).createOnly(eq(NAME), eq(KEY.chatPassword(NAME)));
    verify(matrix, times(1)).ensureOwned(eq(NAME), eq(KEY.chatPassword(NAME)));
  }

  @Test
  void lostIdentityResponseResumesSameCommittedIntentWithoutCreatingAgain() {
    var providerAccount = new AtomicReference<String>();
    when(identity.findOwned(anyString(), anyLong(), anyString()))
        .thenAnswer(call -> Optional.ofNullable(providerAccount.get()));
    when(identity.createOnly(anyString(), anyString(), anyLong(), anyString()))
        .thenAnswer(
            call -> {
              providerAccount.set("owned-id");
              throw new ServiceUnavailableException("Lost provider response");
            });

    assertThatThrownBy(() -> provisioner.provision(KEY))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThat(attempts.findByKeyHash(KEY.attemptHash()).orElseThrow().getPhase().name())
        .isEqualTo("IDENTITY_PENDING");
    var resumed = provisioner.provision(KEY);
    assertThat(resumed.getIdentityUserId()).isEqualTo("owned-id");
    assertThat(resumed.getPhase().name()).isEqualTo("MATRIX_READY");
    verify(identity, times(1)).createOnly(anyString(), anyString(), anyLong(), anyString());
  }

  @Test
  void completedProviderPhasesAreNotExecutedAgainOnRetry() {
    var first = provisioner.provision(KEY);
    var replay = provisioner.provision(KEY);
    assertThat(replay.getId()).isEqualTo(first.getId());
    assertThat(replay.getMatrixUserId()).isEqualTo("@" + NAME + ":matrix.example");
    verify(identity, times(1)).createOnly(anyString(), anyString(), anyLong(), anyString());
    verify(matrix, times(1)).createOnly(anyString(), anyString());
  }

  @Test
  void matrixResponseFailureDoesNotRepeatIdentityCreation() {
    when(matrix.createOnly(anyString(), anyString()))
        .thenThrow(new ServiceUnavailableException("Lost chat response"));
    assertThatThrownBy(() -> provisioner.provision(KEY))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThat(attempts.findByKeyHash(KEY.attemptHash()).orElseThrow().getPhase().name())
        .isEqualTo("MATRIX_PENDING");
    assertThat(provisioner.provision(KEY).getPhase().name()).isEqualTo("MATRIX_READY");
    verify(identity, times(1)).createOnly(anyString(), anyString(), anyLong(), anyString());
    verify(identity, times(1)).completeOwned(anyString(), anyString(), anyLong(), anyString());
    verify(matrix, times(1)).createOnly(anyString(), anyString());
    verify(matrix, times(1)).ensureOwned(anyString(), anyString());
  }

  @Test
  void concurrentCallersExecuteEachProviderPhaseOnce() throws Exception {
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<Long> request =
          () -> {
            assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return provisioner.provision(KEY).getId();
          };
      var first = executor.submit(request);
      var second = executor.submit(request);
      start.countDown();
      assertThat(first.get(20, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo(second.get(20, java.util.concurrent.TimeUnit.SECONDS));
    }
    verify(identity, times(1)).createOnly(anyString(), anyString(), anyLong(), anyString());
    assertThat(mockingDetails(matrix).getInvocations()).hasSize(1);
  }

  @Test
  void revokedInvitationStopsBeforeAnyProviderWrite() {
    invite.setStatus("REVOKED");
    invites.saveAndFlush(invite);
    assertThatThrownBy(() -> provisioner.provision(KEY)).isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(identity, matrix);
  }
}
