package de.caritas.cob.userservice.api.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRevocationTask;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyEnquiryRevocationTaskRepository;
import de.caritas.cob.userservice.api.port.out.AgencyEnquiryRoomRetirementRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.matrix.OrisoRoomRegistry;
import de.caritas.cob.userservice.api.service.session.SessionOwnershipService.OwnershipChange;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  JpaAuditingConfiguration.class,
  AgencyEnquiryRoomRetirements.class,
  AgencyEnquiryRoomDecisions.class,
  AgencyEnquiryRevocations.class,
  OrisoRoomRegistry.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgencyEnquiryRoomRetirementIT {

  private static final AtomicLong AGENCY_IDS = new AtomicLong(1692L);
  private static final long TENANT_ID = 417L;
  private static final long RELATION_ID = 91692L;
  private static final String DEPARTED_ID = "departed-counsellor";
  private static final String DEPARTED_MATRIX_ID = "@departed:example.org";
  private static final String MEMBER_TOKEN = "member-token";
  private static final String REPLACEMENT_ROOM = "!replacement:example.org";

  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private EntityManager entityManager;
  @Autowired private SessionRepository sessions;
  @Autowired private ConsultantRepository consultants;
  @Autowired private AgencyEnquiryRoomRetirementRepository receipts;
  @Autowired private AgencyEnquiryRevocationTaskRepository tasks;
  @Autowired private AgencyEnquiryRoomRetirements retirements;
  @Autowired private AgencyEnquiryRevocations revocations;
  @MockitoBean private SessionRoomGateway rooms;
  @MockitoBean private AgencyMatrixCredentialClient agencyCredentials;
  @MockitoBean private SessionOwnershipService ownership;
  @MockitoBean private Clock clock;

  private final long agencyId = AGENCY_IDS.incrementAndGet();
  private TransactionTemplate transactions;

  @BeforeEach
  void setUp() {
    transactions = new TransactionTemplate(transactionManager);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    when(clock.instant()).thenReturn(Instant.parse("2026-11-18T12:00:00Z"));
  }

  @Test
  void committedReplacementAllowsPendingRemovalOnlyInTheFormerRoom() {
    var oldRoom = "!former-staged:example.org";
    var enquiry = openEnquiry(oldRoom);
    var departed = new Consultant();
    departed.setId(DEPARTED_ID);
    departed.setMatrixUserId(DEPARTED_MATRIX_ID);
    var removed =
        ConsultantAgency.builder().id(RELATION_ID).consultant(departed).agencyId(agencyId).build();

    transactions.executeWithoutResult(status -> revocations.stage(removed));
    assertThat(tasks.pendingSessionIds(RELATION_ID)).contains(enquiry.getId());
    var accepted = accept(enquiry.getId());
    retirements.replaceRoom(accepted, assignment(accepted), REPLACEMENT_ROOM);

    assertThat(currentRoom(enquiry)).isEqualTo(REPLACEMENT_ROOM);
    assertThat(hasReceipt(accepted, oldRoom)).isTrue();
    when(rooms.loginAsUser(DEPARTED_MATRIX_ID)).thenReturn(MEMBER_TOKEN);
    when(rooms.isConfirmedOutOfRoom(oldRoom, DEPARTED_MATRIX_ID)).thenReturn(false, true);

    revocations.retryDue();

    verify(rooms).leaveRoom(oldRoom, MEMBER_TOKEN);
    verify(rooms, never()).leaveRoom(eq(REPLACEMENT_ROOM), any());
    assertThat(tasks.pendingSessionIds(RELATION_ID)).isEmpty();
  }

  @Test
  void rollingBackReplacementAlsoRollsBackItsReceipt() {
    var oldRoom = "!former-rolled-back:example.org";
    var accepted = accept(openEnquiry(oldRoom).getId());

    transactions.executeWithoutResult(
        status -> {
          retirements.replaceRoom(accepted, assignment(accepted), REPLACEMENT_ROOM);
          status.setRollbackOnly();
        });

    assertThat(currentRoom(accepted)).isEqualTo(oldRoom);
    assertThat(hasReceipt(accepted, oldRoom)).isFalse();
  }

  @Test
  void aStaleAcceptanceCannotReplaceTheCurrentRoom() {
    var oldRoom = "!former-stale:example.org";
    var accepted = accept(openEnquiry(oldRoom).getId());
    var assignment = new OwnershipChange(accepted.getId(), accepted.getConsultant().getId(), 0);

    assertThatThrownBy(() -> retirements.replaceRoom(accepted, assignment, REPLACEMENT_ROOM))
        .isInstanceOf(ConflictException.class);

    assertThat(currentRoom(accepted)).isEqualTo(oldRoom);
    assertThat(hasReceipt(accepted, oldRoom)).isFalse();
  }

  @Test
  void aFormerRoomStillBoundToAnotherCaseDoesNotAuthorizeRemoval() {
    var oldRoom = "!former-shared:example.org";
    var accepted = accept(openEnquiry(oldRoom).getId());
    retirements.replaceRoom(accepted, assignment(accepted), REPLACEMENT_ROOM);
    openEnquiry(oldRoom);
    var task =
        AgencyEnquiryRevocationTask.builder()
            .sessionId(accepted.getId())
            .agencyId(agencyId)
            .roomId(oldRoom)
            .build();

    assertThat(hasReceipt(accepted, oldRoom)).isTrue();
    assertThat(retirements.provesRetirement(task, accepted)).isFalse();
  }

  private boolean hasReceipt(Session session, String formerRoom) {
    return receipts.existsByRoomIdAndSessionIdAndAgencyIdAndTenantIdAndReplacementRoomId(
        formerRoom, session.getId(), agencyId, TENANT_ID, REPLACEMENT_ROOM);
  }

  private String currentRoom(Session session) {
    return sessions.findById(session.getId()).orElseThrow().getMatrixRoomId();
  }

  private OwnershipChange assignment(Session session) {
    return new OwnershipChange(
        session.getId(), session.getConsultant().getId(), session.getOwnershipRevision());
  }

  private Session accept(Long sessionId) {
    return transactions.execute(
        status -> {
          var session = sessions.lockFresh(sessionId).orElseThrow();
          var owner = consultants.findAll().iterator().next();
          session.setConsultant(owner);
          session.setStatus(Session.SessionStatus.IN_PROGRESS);
          session.setOwnershipRevision(1);
          entityManager.flush();
          return session;
        });
  }

  private Session openEnquiry(String room) {
    return transactions.execute(
        status -> {
          var asker = new User();
          asker.setUserId(UUID.randomUUID().toString());
          asker.setUsername("enc." + asker.getUserId());
          asker.setEmail(asker.getUserId() + "@example.org");
          asker.setTenantId(TENANT_ID);
          asker.setEncourage2fa(false);
          asker.setMagicLinkLoginEnabled(false);
          asker.setLanguageCode(LanguageCode.de);
          entityManager.persist(asker);
          var session = new Session();
          session.setUser(asker);
          session.setPostcode("12345");
          session.setAgencyId(agencyId);
          session.setStatus(Session.SessionStatus.NEW);
          session.setTenantId(TENANT_ID);
          session.setLanguageCode(LanguageCode.de);
          session.setIsConsultantDirectlySet(false);
          session.setRegistrationType(Session.RegistrationType.REGISTERED);
          session.setMatrixRoomId(room);
          entityManager.persist(session);
          return session;
        });
  }
}
