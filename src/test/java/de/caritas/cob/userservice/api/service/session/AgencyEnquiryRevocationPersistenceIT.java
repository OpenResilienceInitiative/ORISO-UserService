package de.caritas.cob.userservice.api.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.AgencyEnquiryRevocationTaskRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** The pending operation shares the detach transaction and survives across retry transactions. */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AgencyEnquiryRevocations.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgencyEnquiryRevocationPersistenceIT {

  private static final long RELATION_ID = 1692L;
  private static final long SESSION_ID = 1693L;
  private static final long AGENCY_ID = 1694L;
  private static final String CONSULTANT_ID = "test-enquiry-revocation";
  private static final String MEMBER_ID = "@revocation:example.org";
  private static final String OPERATOR_ID = "@agency:example.org";
  private static final String ROOM = "!revocation:example.org";
  private static final String TOKEN = "agency-token";

  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private AgencyEnquiryRevocationTaskRepository tasks;
  @Autowired private AgencyEnquiryRevocations revocations;
  @MockitoBean private Clock clock;
  @MockitoBean private SessionRepository sessions;
  @MockitoBean private ConsultantRepository consultants;
  @MockitoBean private AgencyMatrixCredentialClient agencyCredentials;
  @MockitoBean private SessionRoomGateway rooms;

  @Test
  void retrySurvivesTheCommittedDetachAndIsNotRepeatedAfterConfirmation() {
    Instant start = Instant.parse("2026-11-18T12:00:00Z");
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    when(clock.instant()).thenReturn(start);
    var consultant = new Consultant();
    consultant.setId(CONSULTANT_ID);
    consultant.setMatrixUserId(MEMBER_ID);
    var relationship =
        ConsultantAgency.builder()
            .id(RELATION_ID)
            .consultant(consultant)
            .agencyId(AGENCY_ID)
            .build();
    var room = new Session();
    room.setId(SESSION_ID);
    room.setAgencyId(AGENCY_ID);
    room.setStatus(SessionStatus.NEW);
    room.setMatrixRoomId(ROOM);
    when(sessions.findByAgencyIdAndStatusAndConsultantIsNull(AGENCY_ID, SessionStatus.NEW))
        .thenReturn(List.of(room));
    when(sessions.findByIdForUpdate(SESSION_ID)).thenReturn(Optional.of(room));
    var operator = new AgencyMatrixCredentialsDTO();
    operator.setMatrixUserId(OPERATOR_ID);
    when(agencyCredentials.fetchMatrixCredentials(AGENCY_ID)).thenReturn(Optional.of(operator));
    when(rooms.loginAsUser(OPERATOR_ID)).thenReturn(TOKEN);
    when(rooms.removeUserFromRoom(ROOM, MEMBER_ID, TOKEN)).thenReturn(false, true);

    var transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(
        status -> {
          revocations.stage(relationship);
          status.setRollbackOnly();
        });
    assertThat(tasks.pendingSessionIds(RELATION_ID)).isEmpty();

    transaction.executeWithoutResult(status -> revocations.stage(relationship));
    var pending = tasks.dueIds(LocalDateTime.now(clock), PageRequest.of(0, 1));
    assertThat(pending).hasSize(1);

    revocations.retryDue();

    assertThat(tasks.findById(pending.getFirst()))
        .get()
        .extracting(task -> task.getRetryAt())
        .isEqualTo(LocalDateTime.now(clock).plus(AgencyEnquiryRevocations.RETRY_DELAY));
    when(clock.instant()).thenReturn(start.plus(AgencyEnquiryRevocations.RETRY_DELAY));

    revocations.retryDue();
    revocations.retryDue();

    assertThat(tasks.pendingSessionIds(RELATION_ID)).isEmpty();
    verify(rooms, times(2)).removeUserFromRoom(ROOM, MEMBER_ID, TOKEN);
  }
}
