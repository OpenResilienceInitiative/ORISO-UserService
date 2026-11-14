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
@Import({DirectInvitationExpiryService.class, DirectInvitationExpiryCommitIT.TestClock.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DirectInvitationExpiryCommitIT {

  private static final String GUEST_ID = "expiry-commit-guest";
  private static final String GUEST_MATRIX_ID = "@expiry-commit-guest:x";

  @Autowired private DirectInvitationExpiryService expiry;
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
          members.deleteById(memberId);
          links.deleteById(linkId);
          appointments.deleteById(appointmentId);
          sessions.deleteById(sessionId);
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
