package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.facade.CreateNewSessionFacade;
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
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CounsellorDirectLinkService.UpdateCommand;
import de.caritas.cob.userservice.api.service.archive.SessionArchiveService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
 * ORISO-Frontend#2252, CodeRabbit on PR #1648: moving an appointment through one of its
 * invitations, against a real database and committed for real. An invitation already past its end
 * ends (status and revocation committed) instead of coming back to life, and two moves through two
 * invitations of one appointment run one after the other instead of deadlocking. Own rows, removed
 * again; the shared test data is only read.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({
  CounsellorDirectLinkService.class,
  DirectInvitationService.class,
  DirectInvitationMemberships.class,
  DirectInvitationExpiryService.class,
  DirectInvitationMoveIT.TestClock.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DirectInvitationMoveIT {

  @Autowired private CounsellorDirectLinkService directLinks;
  @Autowired private AgencyInviteLinkRepository links;
  @Autowired private SessionAppointmentRepository appointments;
  @Autowired private DirectInvitationMemberRepository members;
  @Autowired private SessionRepository sessions;
  @Autowired private ConsultantRepository consultants;
  @Autowired private EntityManager entityManager;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private javax.sql.DataSource dataSource;

  @MockitoBean private AgencyService agencyService;
  @MockitoBean private TopicService topicService;
  @MockitoBean private DirectInvitePolicy directInvitePolicy;
  @MockitoBean private CreateNewSessionFacade createNewSessionFacade;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private DirectCallKnockService directCallKnockService;
  @MockitoBean private DirectInvitationRoomMembership rooms;
  @MockitoBean private EventNotificationService notifications;
  @MockitoBean private MatrixSessionSystemMessageService systemMessages;
  @MockitoBean private SessionArchiveService archive;
  @MockitoBean private de.caritas.cob.userservice.api.port.out.UserRepository users;

  private TransactionTemplate tx;
  private Consultant owner;
  private Long sessionId;
  private UUID appointmentId;
  private Long overdueId;
  private Long openId;
  private Long memberId;

  /** Run before and after every locking read of an invitation; set by the concurrency test. */
  private static volatile Runnable beforeLockingRead = () -> {};

  private static volatile Runnable afterLockingRead = () -> {};

  @TestConfiguration
  static class TestClock {
    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }

    /** Lets the test hold a move right after it took its first invitation lock. */
    @Bean
    static org.springframework.beans.factory.config.BeanPostProcessor lockingReadHook() {
      return new org.springframework.beans.factory.config.BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
          if (!(bean instanceof AgencyInviteLinkRepository repository)) {
            return bean;
          }
          return java.lang.reflect.Proxy.newProxyInstance(
              AgencyInviteLinkRepository.class.getClassLoader(),
              new Class<?>[] {AgencyInviteLinkRepository.class},
              (proxy, method, args) -> {
                Object result;
                if (method.getName().endsWith("ForUpdate")) {
                  beforeLockingRead.run();
                }
                try {
                  result = method.invoke(repository, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                  throw e.getCause();
                }
                if (method.getName().endsWith("ForUpdate")) {
                  afterLockingRead.run();
                }
                return result;
              });
        }
      };
    }
  }

  /**
   * One appointment that ended an hour ago, with one invitation past its end and one still open.
   */
  @BeforeEach
  void givenAnAppointmentWithAnOverdueAndAnOpenInvitation() {
    tx = new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(
        status -> {
          var asker =
              entityManager
                  .createQuery("SELECT u FROM User u ORDER BY u.userId", User.class)
                  .setMaxResults(1)
                  .getSingleResult();
          owner = consultants.findAll().iterator().next();
          var session = new Session();
          session.setUser(asker);
          session.setConsultant(owner);
          session.setConsultingTypeId(1);
          session.setRegistrationType(Session.RegistrationType.REGISTERED);
          session.setPostcode("12345");
          session.setLanguageCode(com.neovisionaries.i18n.LanguageCode.de);
          session.setStatus(Session.SessionStatus.IN_PROGRESS);
          session.setIsConsultantDirectlySet(false);
          session.setConversationType(ConversationType.AGENCY_COUNSELLING);
          session.setMatrixRoomId("!move:x");
          sessionId = sessions.save(session).getId();
          var appointment = new SessionAppointment();
          appointment.setSession(session);
          appointment.setStatus(SessionAppointment.Status.SCHEDULED);
          var start = Instant.now().minus(2, ChronoUnit.HOURS);
          appointment.setStartTime(start);
          appointment.setEndTime(start.plus(1, ChronoUnit.HOURS));
          appointmentId = appointments.save(appointment).getId();
          var end = LocalDateTime.ofInstant(start.plus(1, ChronoUnit.HOURS), ZoneOffset.UTC);
          overdueId = invitation("move-overdue", 0, end).getId();
          openId = invitation("move-open", 600, end.plusMinutes(600)).getId();
          memberId =
              members
                  .save(
                      DirectInvitationMember.builder()
                          .sessionId(sessionId)
                          .userId("move-guest")
                          .inviteLinkId(overdueId)
                          .guest(true)
                          .joinedAt(end.minusMinutes(30))
                          .build())
                  .getId();
        });
  }

  @AfterEach
  void removeCommittedRows() {
    beforeLockingRead = () -> {};
    afterLockingRead = () -> {};
    tx.executeWithoutResult(
        status -> {
          members.findById(memberId).ifPresent(members::delete);
          links.findById(overdueId).ifPresent(links::delete);
          links.findById(openId).ifPresent(links::delete);
          appointments.findById(appointmentId).ifPresent(appointments::delete);
          sessions.findById(sessionId).ifPresent(sessions::delete);
        });
  }

  @Test
  void move_Should_EndAnOverdueInvitation_AndCommitItsRevocation() {
    var start = LocalDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0);
    var guest = new User();
    guest.setUserId("move-guest");
    guest.setMatrixUserId("@move-guest:oriso");
    org.mockito.Mockito.when(users.findById("move-guest")).thenReturn(java.util.Optional.of(guest));
    org.mockito.Mockito.when(
            rooms.remove(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(true);

    directLinks.update(owner, openId, move(start));

    var overdue = tx.execute(status -> links.findById(overdueId).orElseThrow());
    var open = tx.execute(status -> links.findById(openId).orElseThrow());
    var member = tx.execute(status -> members.findById(memberId).orElseThrow());
    assertThat(overdue.getStatus()).isEqualTo(InviteLinkStatus.EXPIRED.name());
    assertThat(open.getStatus()).isEqualTo(InviteLinkStatus.ACTIVE.name());
    assertThat(open.getExpiresAt()).isEqualTo(start.plusHours(1).plusMinutes(600));
    assertThat(member.getRemovedAt()).as("the revocation is committed").isNotNull();
    org.mockito.Mockito.verify(rooms)
        .remove(
            org.mockito.ArgumentMatchers.argThat(session -> sessionId.equals(session.getId())),
            org.mockito.ArgumentMatchers.eq("@move-guest:oriso"));
  }

  /**
   * CodeRabbit on PR #1648: the two moves overlap while they take their locks. The first one is
   * held right after its first invitation lock, the second one starts and the database reports it
   * waiting. Taking the moved invitation's own lock first instead would leave each move holding one
   * invitation and waiting for the other: a deadlock, which fails this test.
   */
  @Test
  void movesThroughTwoInvitations_Should_RunOneAfterTheOther() throws Exception {
    var first = LocalDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0);
    var second = first.plusDays(1);
    var holding = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var heldOnce = new java.util.concurrent.atomic.AtomicBoolean();
    var holder = new java.util.concurrent.atomic.AtomicInteger();
    var waiter = new java.util.concurrent.atomic.AtomicInteger();
    var secondAsks = new CountDownLatch(1);
    afterLockingRead =
        () -> {
          if (heldOnce.compareAndSet(false, true)) {
            holder.set(LockWaits.currentSession(dataSource));
            holding.countDown();
            await(release);
          }
        };
    // Set once the first move is held: the next locking read is the second move's first one.
    beforeLockingRead =
        () -> {
          if (heldOnce.get() && secondAsks.getCount() > 0) {
            waiter.set(LockWaits.currentSession(dataSource));
            secondAsks.countDown();
          }
        };
    var firstMove =
        CompletableFuture.runAsync(() -> directLinks.update(owner, overdueId, move(first)));
    assertThat(holding.await(10, TimeUnit.SECONDS))
        .as("the first move took its first lock")
        .isTrue();

    var secondMove =
        CompletableFuture.runAsync(() -> directLinks.update(owner, openId, move(second)));
    assertThat(secondAsks.await(10, TimeUnit.SECONDS))
        .as("the second move reached its locking read")
        .isTrue();
    LockWaits.awaitBlockedBy(dataSource, waiter.get(), holder.get());
    assertThat(secondMove).as("waits for the first move's locks").isNotDone();
    release.countDown();
    firstMove.get(10, TimeUnit.SECONDS);
    secondMove.get(10, TimeUnit.SECONDS);

    var appointment = tx.execute(status -> appointments.findById(appointmentId).orElseThrow());
    assertThat(appointment.getStartTime()).isEqualTo(second.toInstant(ZoneOffset.UTC));
  }

  private UpdateCommand move(LocalDateTime start) {
    return new UpdateCommand(
        null, null, null, null, null, false, start, start.plusHours(1), null, null, null);
  }

  private AgencyInviteLink invitation(String token, int validity, LocalDateTime expiresAt) {
    return links.save(
        AgencyInviteLink.builder()
            .token(token)
            .tenantId(1L)
            .linkKind(InviteLinkKind.COUNSELLOR.name())
            .chatType(InviteLinkChatType.VIDEO.name())
            .anonymity("NONE")
            .consultantId(owner.getId())
            .createdByUserId(owner.getId())
            .createDate(LocalDateTime.now(ZoneOffset.UTC))
            .status(InviteLinkStatus.ACTIVE.name())
            .appointmentId(appointmentId)
            .joinsExistingSession(true)
            .validityAfterEndMinutes(validity)
            .expiresAt(expiresAt)
            .build());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("not released");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
