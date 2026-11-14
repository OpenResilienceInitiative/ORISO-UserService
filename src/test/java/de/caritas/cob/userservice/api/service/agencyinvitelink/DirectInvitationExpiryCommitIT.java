package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.DirectInvitationMember;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.DirectInvitationMemberRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.archive.SessionArchiveService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ORISO-Frontend#2252, CodeRabbit on PR #1648: the commit boundary of an invitation's end against a
 * real database and its transaction manager. Matrix is asked only once the expiry is committed
 * (another connection already reads it), and never when the expiry is rolled back. Own rows,
 * committed for real and removed again; the shared test data is only read.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({
  DirectInvitationExpiryService.class,
  DirectInvitationMemberships.class,
  DirectInvitationExpiryCommitIT.TestClock.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DirectInvitationExpiryCommitIT {

  private static final String GUEST_ID = "expiry-commit-guest";
  private static final String GUEST_MATRIX_ID = "@expiry-commit-guest:x";

  @Autowired private DirectInvitationExpiryService expiry;
  @Autowired private DirectInvitationMemberships memberships;
  @Autowired private AgencyInviteLinkRepository links;
  @Autowired private SessionAppointmentRepository appointments;
  @Autowired private DirectInvitationMemberRepository members;
  @Autowired private SessionRepository sessions;
  @Autowired private ConsultantRepository consultants;
  @Autowired private EntityManager entityManager;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private DirectInvitationRoomMembership rooms;
  @MockitoBean private SessionArchiveService archive;
  @MockitoBean private UserRepository users;

  private TransactionTemplate tx;
  private Long sessionId;
  private UUID appointmentId;
  private Long linkId;
  private Long memberId;
  private Long secondMemberId;

  @TestConfiguration
  static class TestClock {
    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }

  @BeforeEach
  void givenAnOverdueInvitationIntoAnExisting1to1WithAGuest() {
    tx = new TransactionTemplate(transactionManager);
    var guest = new User();
    guest.setUserId(GUEST_ID);
    guest.setMatrixUserId(GUEST_MATRIX_ID);
    when(users.findById(GUEST_ID)).thenReturn(Optional.of(guest));
    tx.executeWithoutResult(
        status -> {
          var owner =
              entityManager
                  .createQuery("SELECT u FROM User u ORDER BY u.userId", User.class)
                  .setMaxResults(1)
                  .getSingleResult();
          Consultant consultant = consultants.findAll().iterator().next();
          var session = new Session();
          session.setUser(owner);
          session.setConsultant(consultant);
          session.setConsultingTypeId(1);
          session.setRegistrationType(Session.RegistrationType.REGISTERED);
          session.setPostcode("12345");
          session.setLanguageCode(com.neovisionaries.i18n.LanguageCode.de);
          session.setStatus(Session.SessionStatus.IN_PROGRESS);
          session.setIsConsultantDirectlySet(false);
          session.setConversationType(ConversationType.AGENCY_COUNSELLING);
          session.setMatrixRoomId("!expiry-commit:x");
          sessionId = sessions.save(session).getId();
          var appointment = new SessionAppointment();
          appointment.setSession(session);
          appointment.setStatus(SessionAppointment.Status.SCHEDULED);
          var start = Instant.now().minus(2, ChronoUnit.HOURS);
          appointment.setStartTime(start);
          appointment.setEndTime(start.plus(1, ChronoUnit.HOURS));
          appointmentId = appointments.save(appointment).getId();
          linkId =
              links
                  .save(
                      AgencyInviteLink.builder()
                          .token("expiry-commit-token")
                          .tenantId(1L)
                          .linkKind(InviteLinkKind.COUNSELLOR.name())
                          .chatType(InviteLinkChatType.VIDEO.name())
                          .anonymity("NONE")
                          .consultantId(consultant.getId())
                          .createdByUserId(consultant.getId())
                          .createDate(LocalDateTime.now(ZoneOffset.UTC))
                          .status(InviteLinkStatus.ACTIVE.name())
                          .appointmentId(appointmentId)
                          .joinsExistingSession(true)
                          .validityAfterEndMinutes(0)
                          .expiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5))
                          .build())
                  .getId();
          memberId =
              members
                  .save(
                      DirectInvitationMember.builder()
                          .sessionId(sessionId)
                          .userId(GUEST_ID)
                          .inviteLinkId(linkId)
                          .guest(true)
                          .joinedAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(2))
                          .build())
                  .getId();
        });
  }

  @AfterEach
  void removeCommittedRows() {
    tx.executeWithoutResult(
        status -> {
          members.findBySessionIdAndRemovedAtIsNull(sessionId).forEach(members::delete);
          members.findById(memberId).ifPresent(members::delete);
          if (secondMemberId != null) {
            members.findById(secondMemberId).ifPresent(members::delete);
          }
          links.deleteById(linkId);
          appointments.findById(appointmentId).ifPresent(appointments::delete);
          sessions.findById(sessionId).ifPresent(sessions::delete);
        });
  }

  @Test
  void expire_Should_AskMatrix_OnlyWhenAnotherConnectionReadsTheCommittedExpiry() {
    var seenByAnotherConnection = new ArrayList<String>();
    when(rooms.remove(any(), any()))
        .thenAnswer(
            call -> {
              // Another thread, so another connection and transaction: only committed rows.
              seenByAnotherConnection.add(
                  CompletableFuture.supplyAsync(
                          () ->
                              tx.execute(
                                  status -> links.findById(linkId).orElseThrow().getStatus()))
                      .get(10, TimeUnit.SECONDS));
              return true;
            });

    assertThat(expiry.expire(linkId)).isTrue();

    verify(rooms).remove(any(), org.mockito.ArgumentMatchers.eq(GUEST_MATRIX_ID));
    assertThat(seenByAnotherConnection).containsExactly(InviteLinkStatus.EXPIRED.name());
    var member = tx.execute(status -> members.findById(memberId).orElseThrow());
    assertThat(member.getRemovedAt()).as("the removal is committed too").isNotNull();
  }

  /**
   * CodeRabbit on PR #1648: a deletion refused because Matrix keeps one person in rolls back, yet
   * both people asked about stay due in the database, so the sweep confirms the one already out and
   * retries the other.
   */
  @Test
  void requireInvitedOutBeforeDeletion_Should_KeepBothRemovalsDue_When_OneOfThemFails() {
    var account = new User();
    account.setUserId("expiry-commit-account");
    account.setMatrixUserId("@expiry-commit-account:x");
    when(users.findById("expiry-commit-account")).thenReturn(Optional.of(account));
    secondMemberId =
        tx.execute(
            status ->
                members
                    .save(
                        DirectInvitationMember.builder()
                            .sessionId(sessionId)
                            .userId("expiry-commit-account")
                            .inviteLinkId(linkId)
                            .guest(false)
                            .joinedAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(2))
                            .build())
                    .getId());
    when(rooms.remove(any(), org.mockito.ArgumentMatchers.eq(GUEST_MATRIX_ID))).thenReturn(true);
    when(rooms.remove(any(), org.mockito.ArgumentMatchers.eq("@expiry-commit-account:x")))
        .thenReturn(false);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status ->
                        expiry.requireInvitedOutBeforeDeletion(
                            sessions.findById(sessionId).orElseThrow())))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException
                .class);

    var due =
        tx.execute(
            status ->
                List.of(
                    members.findById(memberId).orElseThrow().getRevokeDueAt() != null,
                    members.findById(secondMemberId).orElseThrow().getRevokeDueAt() != null));
    assertThat(due).containsExactly(true, true);
  }

  /**
   * CodeRabbit on PR #1648: a join reserving its place while the conversation is being deleted
   * waits for the deletion and then lets nobody in, instead of leaving a member row the deletion
   * never saw.
   */
  @Test
  void reserve_Should_WaitForADeletion_AndLetNobodyIn() throws Exception {
    var locked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    when(rooms.remove(any(), any())).thenReturn(true);
    var deletion =
        CompletableFuture.runAsync(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      expiry.requireInvitedOutBeforeDeletion(
                          sessions.findById(sessionId).orElseThrow());
                      locked.countDown();
                      awaitQuietly(release);
                      members.findBySessionIdAndRemovedAtIsNull(sessionId).forEach(members::delete);
                      members.findById(memberId).ifPresent(members::delete);
                      appointments.findById(appointmentId).ifPresent(appointments::delete);
                      sessions.deleteById(sessionId);
                    }));
    assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

    var latecomer = new User();
    latecomer.setUserId("expiry-commit-latecomer");
    var session = tx.execute(status -> sessions.findById(sessionId).orElseThrow());
    var link = tx.execute(status -> links.findById(linkId).orElseThrow());
    var join = CompletableFuture.supplyAsync(() -> memberships.reserve(session, latecomer, link));
    Thread.sleep(300);
    assertThat(join).as("the reservation waits for the deletion's lock").isNotDone();
    release.countDown();
    deletion.get(10, TimeUnit.SECONDS);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> join.get(10, TimeUnit.SECONDS))
        .hasCauseInstanceOf(DirectLinkInactiveException.class);
    Optional<DirectInvitationMember> latecomerRow =
        tx.execute(
            status ->
                members.findBySessionIdAndUserIdForUpdate(sessionId, "expiry-commit-latecomer"));
    assertThat(latecomerRow).isEmpty();
  }

  private static void awaitQuietly(java.util.concurrent.CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("not released");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  @Test
  void expire_Should_NeitherAskMatrixNorKeepAnything_When_ItsTransactionRollsBack() {
    tx.executeWithoutResult(
        status -> {
          assertThat(expiry.expire(linkId)).isTrue();
          status.setRollbackOnly();
        });

    verify(rooms, never()).remove(any(), any());
    var stored =
        tx.execute(
            status ->
                List.of(
                    links.findById(linkId).orElseThrow().getStatus(),
                    String.valueOf(members.findById(memberId).orElseThrow().getRevokeDueAt())));
    assertThat(stored).containsExactly(InviteLinkStatus.ACTIVE.name(), "null");
  }
}
