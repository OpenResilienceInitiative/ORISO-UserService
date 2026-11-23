package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;

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
import de.caritas.cob.userservice.api.service.archive.SessionArchiveService;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
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
 * ORISO-UserService#1694: opening a scheduled direct invitation again, against a real database and
 * without a surrounding transaction, the way the request runs (spring.jpa.open-in-view=false). The
 * conversation that comes back is read after {@code openAppointmentConversation} committed, so it
 * must be loaded, not a lazy reference of the appointment. On Dev a lazy one answered every second
 * visit with HTTP 500 ("Dieser Einladungslink kann nicht mehr verwendet werden").
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({
  DirectInvitationService.class,
  DirectInvitationMemberships.class,
  DirectInvitationReopenIT.TestClock.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DirectInvitationReopenIT {

  private static final String ROOM = "!reopen-1694:x";

  @Autowired private DirectInvitationService invitations;
  @Autowired private AgencyInviteLinkRepository links;
  @Autowired private SessionAppointmentRepository appointments;
  @Autowired private DirectInvitationMemberRepository members;
  @Autowired private SessionRepository sessions;
  @Autowired private ConsultantRepository consultants;
  @Autowired private EntityManager entityManager;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private DirectInvitationRoomMembership rooms;
  @MockitoBean private EventNotificationService notifications;
  @MockitoBean private MatrixSessionSystemMessageService systemMessages;
  @MockitoBean private SessionArchiveService archive;

  private TransactionTemplate tx;
  private User firstParticipant;
  private Consultant owner;
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

  /** The state after the first visit: conversation created, its creator recorded as member. */
  @BeforeEach
  void givenAnOpenedAppointmentConversation() {
    tx = new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(
        status -> {
          firstParticipant =
              entityManager
                  .createQuery("SELECT u FROM User u ORDER BY u.userId", User.class)
                  .setMaxResults(1)
                  .getSingleResult();
          owner = consultants.findAll().iterator().next();
          var session = new Session();
          session.setUser(firstParticipant);
          session.setConsultant(owner);
          session.setConsultingTypeId(1);
          session.setRegistrationType(Session.RegistrationType.REGISTERED);
          session.setPostcode("12345");
          session.setLanguageCode(com.neovisionaries.i18n.LanguageCode.de);
          session.setStatus(Session.SessionStatus.IN_PROGRESS);
          session.setIsConsultantDirectlySet(false);
          session.setConversationType(ConversationType.AGENCY_COUNSELLING);
          session.setMatrixRoomId(ROOM);
          sessionId = sessions.save(session).getId();
          var appointment = new SessionAppointment();
          appointment.setSession(session);
          appointment.setStatus(SessionAppointment.Status.SCHEDULED);
          var start = Instant.now().plus(1, ChronoUnit.DAYS);
          appointment.setStartTime(start);
          appointment.setEndTime(start.plus(1, ChronoUnit.HOURS));
          appointmentId = appointments.save(appointment).getId();
          linkId =
              links
                  .save(
                      AgencyInviteLink.builder()
                          .token("reopen-1694")
                          .tenantId(1L)
                          .linkKind(InviteLinkKind.COUNSELLOR.name())
                          .chatType(InviteLinkChatType.VIDEO.name())
                          .anonymity("NONE")
                          .consultantId(owner.getId())
                          .createdByUserId(owner.getId())
                          .createDate(LocalDateTime.now(ZoneOffset.UTC))
                          .status(InviteLinkStatus.ACTIVE.name())
                          .appointmentId(appointmentId)
                          .validityAfterEndMinutes(60)
                          .expiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(2))
                          .build())
                  .getId();
          memberId =
              members
                  .save(
                      DirectInvitationMember.builder()
                          .sessionId(sessionId)
                          .userId(firstParticipant.getUserId())
                          .inviteLinkId(linkId)
                          .joinedAt(LocalDateTime.now(ZoneOffset.UTC))
                          .build())
                  .getId();
        });
  }

  @AfterEach
  void removeCommittedRows() {
    tx.executeWithoutResult(
        status -> {
          members.findById(memberId).ifPresent(members::delete);
          links.findById(linkId).ifPresent(links::delete);
          appointments.findById(appointmentId).ifPresent(appointments::delete);
          sessions.findById(sessionId).ifPresent(sessions::delete);
        });
  }

  @Test
  void openAppointmentConversation_Should_ReturnALoadedConversation_ForTheFirstParticipant() {
    var link = tx.execute(status -> links.findById(linkId).orElseThrow());

    var opened =
        invitations.openAppointmentConversation(
            link,
            firstParticipant,
            () -> {
              throw new AssertionError("the conversation exists already");
            });

    assertThat(opened.created()).isFalse();
    assertThat(opened.joinPending()).isFalse();
    // Read after the transaction ended, like CounsellorDirectLinkService does.
    assertThat(opened.session().getId()).isEqualTo(sessionId);
    assertThat(opened.session().getMatrixRoomId()).isEqualTo(ROOM);
  }

  @Test
  void openAppointmentConversation_Should_ReturnALoadedConversation_ForALaterParticipant() {
    var link = tx.execute(status -> links.findById(linkId).orElseThrow());
    var later = new User();
    later.setUserId("reopen-1694-later");

    var opened =
        invitations.openAppointmentConversation(
            link,
            later,
            () -> {
              throw new AssertionError("the conversation exists already");
            });

    assertThat(opened.joinPending()).isTrue();
    // The join after this transaction reads the room and the conversation's counsellor.
    assertThat(opened.session().getMatrixRoomId()).isEqualTo(ROOM);
    assertThat(opened.session().getConsultant().getId()).isEqualTo(owner.getId());
  }
}
