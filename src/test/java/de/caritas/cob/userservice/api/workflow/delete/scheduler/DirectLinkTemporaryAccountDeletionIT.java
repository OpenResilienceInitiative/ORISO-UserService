package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.TemporaryAccountSnapshot;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.session.SessionMapper;
import de.caritas.cob.userservice.api.service.temporaryaccount.MessageRetentionMode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The direct-link cleanup (#1476) against the real schema, repositories and scheduler: a guest of a
 * seeded 1:1 session is turned into a temporary account, then the job runs the way it runs in
 * production. Keycloak and Matrix are the only mocks.
 */
@SpringBootTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DirectLinkTemporaryAccountDeletionIT {

  // Seeded asker with session 1 (UserServiceDatabase.sql).
  private static final String USER_ID = "1da238c6-cd46-4162-80f1-bff74eafe77f";
  private static final long SESSION_ID = 1L;
  private static final String MATRIX_ID = "@direct-link-guest:matrix.example";

  @Autowired private DeleteTemporaryAccountsScheduler scheduler;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  private final SessionMapper sessionMapper = new SessionMapper();
  @Autowired private TransactionTemplate transactionTemplate;

  @MockitoBean private MatrixSynapseService matrixSynapseService;
  @MockitoBean private KeycloakService keycloakService;

  private String originalMatrixId;
  private final java.util.Map<Long, SessionStatus> originalStatus = new java.util.HashMap<>();

  @BeforeEach
  void turnTheSeededAskerIntoAGuest() {
    when(matrixSynapseService.setAccountSuspended(
            anyString(), org.mockito.ArgumentMatchers.eq(true)))
        .thenReturn(true);
    when(matrixSynapseService.submitRedaction(anyString(), anyList()))
        .thenReturn(java.util.Optional.of("r1"));
    when(matrixSynapseService.redactionStatus("r1"))
        .thenReturn(MatrixSynapseService.RedactionStatus.COMPLETE);
    when(matrixSynapseService.deactivateUser(anyString(), anyBoolean())).thenReturn(true);
    var user = userRepository.findById(USER_ID).orElseThrow();
    originalMatrixId = user.getMatrixUserId();
    user.setMatrixUserId(MATRIX_ID);
    user.setTemporaryAccount(true);
    user.setTemporaryAccountSnapshot(snapshot(MessageRetentionMode.ALWAYS_PERSISTENT));
    userRepository.save(user);
    sessionRepository
        .findByUser(user)
        .forEach(session -> originalStatus.put(session.getId(), session.getStatus()));
  }

  @AfterEach
  void restore() {
    var user = userRepository.findById(USER_ID).orElseThrow();
    user.setMatrixUserId(originalMatrixId);
    user.setTemporaryAccount(false);
    user.setTemporaryAccountSnapshot(null);
    userRepository.save(user);
    originalStatus.forEach(
        (id, status) -> {
          var session = sessionRepository.findById(id).orElseThrow();
          session.setStatus(status);
          sessionRepository.save(session);
        });
  }

  private static TemporaryAccountSnapshot snapshot(MessageRetentionMode mode) {
    return TemporaryAccountSnapshot.builder()
        .medium(de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType.CHAT)
        .inviteLinkId(9L)
        .callDeleteAfterMinutes(15)
        .callMaxLifetimeHours(24)
        .chatInactivityDays(36500)
        .messageRetention(mode)
        .build();
  }

  /** The seeded asker has two sessions; a guest's conversation counts as closed when all are. */
  private void setSessionStatus(SessionStatus status) {
    originalStatus
        .keySet()
        .forEach(
            id -> {
              var session = sessionRepository.findById(id).orElseThrow();
              session.setStatus(status);
              sessionRepository.save(session);
            });
  }

  @Test
  void anOpenConversationKeepsTheGuest_AndArchivingItDeletesTheGuestButNotTheConversation() {
    setSessionStatus(SessionStatus.IN_PROGRESS);

    scheduler.performDirectLinkDeletionWorkflow();

    assertThat(userRepository.findById(USER_ID).orElseThrow().getTemporaryAccountSnapshot())
        .satisfies(copy -> assertThat(copy.getDeletedAt()).isNull());
    verify(keycloakService, never()).deleteUser(USER_ID);

    setSessionStatus(SessionStatus.IN_ARCHIVE);
    scheduler.performDirectLinkDeletionWorkflow();

    var guest = userRepository.findById(USER_ID).orElseThrow();
    assertThat(guest.getTemporaryAccountSnapshot().getDeletedAt()).isNotNull();
    assertThat(sessionRepository.findById(SESSION_ID)).isPresent();
    verify(keycloakService).deleteUser(USER_ID);
    verify(matrixSynapseService, never()).submitRedaction(anyString(), anyList());
    verify(matrixSynapseService).deactivateUser(MATRIX_ID, false);

    // The counsellor's session list now shows the former guest as deleted ("Gast (gelöscht)").
    var sessionUser =
        transactionTemplate.execute(
            status ->
                sessionMapper
                    .toConsultantSessionDto(sessionRepository.findById(SESSION_ID).orElseThrow())
                    .getUser());
    assertThat(sessionUser.isDeleted()).isTrue();
    assertThat(sessionUser.getTemporaryAccount().getDeleted()).isTrue();
    assertThat(sessionUser.getTemporaryAccount().getKeepsMessages()).isTrue();
  }

  @Test
  void alwaysTemporaryDeletesTheGuestsMessagesInTheirRoom() {
    var user = userRepository.findById(USER_ID).orElseThrow();
    user.setTemporaryAccountSnapshot(snapshot(MessageRetentionMode.ALWAYS_TEMPORARY));
    userRepository.save(user);
    setSessionStatus(SessionStatus.DONE);

    scheduler.performDirectLinkDeletionWorkflow();

    var rooms =
        originalStatus.keySet().stream()
            .map(id -> sessionRepository.findById(id).orElseThrow().getMatrixRoomId())
            .filter(room -> room != null && !room.isBlank())
            .toList();
    verify(matrixSynapseService)
        .submitRedaction(
            org.mockito.ArgumentMatchers.eq(MATRIX_ID),
            org.mockito.ArgumentMatchers.argThat(
                given -> given.size() == rooms.size() && given.containsAll(rooms)));
    verify(matrixSynapseService).deactivateUser(MATRIX_ID, true);
  }

  @Test
  void theSnapshotSurvivesAReload_AndTheSelfHelpGroupJobLeavesDirectLinkGuestsAlone() {
    var copy = userRepository.findById(USER_ID).orElseThrow().getTemporaryAccountSnapshot();
    assertThat(copy.getMedium())
        .isEqualTo(de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType.CHAT);
    assertThat(copy.getChatInactivityDays()).isEqualTo(36500);
    assertThat(copy.getMessageRetention()).isEqualTo(MessageRetentionMode.ALWAYS_PERSISTENT);

    assertThat(userRepository.findTemporaryAccountIdsCreatedBefore(LocalDateTime.now().plusDays(1)))
        .doesNotContain(USER_ID);
    assertThat(userRepository.findLiveDirectLinkTemporaryAccountIds()).contains(USER_ID);
  }
}
