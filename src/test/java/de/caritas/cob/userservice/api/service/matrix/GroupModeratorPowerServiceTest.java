package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * ORISO-Frontend#2229: owners and co-moderators of a group are its moderators and hold the Matrix
 * moderator level; a participant does not. Raising is best effort (a missing level only withholds
 * blurring), taking it back on demotion is strict: a demoted moderator must not keep blurring,
 * kicking or banning.
 */
@ExtendWith(MockitoExtension.class)
class GroupModeratorPowerServiceTest {

  private static final String ROOM = "!group:oriso.test";
  private static final String OWNER = "@owner:oriso.test";
  private static final String MEMBER = "@member:oriso.test";
  private static final String FORMER = "@former:oriso.test";

  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private GroupChatParticipantRepository participantRepository;
  @Mock private ConsultantRepository consultantRepository;

  @InjectMocks private GroupModeratorPowerService service;

  private static Chat group() {
    var owner = new Consultant();
    owner.setId("owner-id");
    owner.setMatrixUserId(OWNER);
    var chat = new Chat();
    chat.setId(7L);
    chat.setMatrixRoomId(ROOM);
    chat.setChatOwner(owner);
    return chat;
  }

  @Test
  void ownersAndCoModeratorsAreModeratorsParticipantsAndLeaversAreNot() {
    assertThat(GroupModeratorPowerService.isModerator(ParticipantRole.OWNER)).isTrue();
    assertThat(GroupModeratorPowerService.isModerator(ParticipantRole.CO_MODERATOR)).isTrue();
    assertThat(GroupModeratorPowerService.isModerator(ParticipantRole.PARTICIPANT)).isFalse();
    assertThat(GroupModeratorPowerService.isModerator(null)).isFalse();
  }

  @Test
  void aCoModeratorIsRaisedWithTheGroupOwnerAsPreferredHolder() {
    when(matrixSynapseService.syncModeratorPower(ROOM, Map.of(MEMBER, true), OWNER))
        .thenReturn(true);

    assertThat(service.reconcile(group(), MEMBER, ParticipantRole.CO_MODERATOR)).isTrue();
  }

  @Test
  void aParticipantOrSomeoneWhoLeftLosesTheModeratorLevel() {
    when(matrixSynapseService.syncModeratorPower(ROOM, Map.of(MEMBER, false), OWNER))
        .thenReturn(true);

    assertThat(service.reconcile(group(), MEMBER, ParticipantRole.PARTICIPANT)).isTrue();
    assertThat(service.reconcile(group(), MEMBER, null)).isTrue();
  }

  @Test
  void aFailedRaiseIsReportedButNeverThrown() {
    when(matrixSynapseService.syncModeratorPower(anyString(), anyMap(), anyString()))
        .thenReturn(false);

    assertThat(service.reconcile(group(), MEMBER, ParticipantRole.OWNER)).isFalse();
  }

  @Test
  void aDemotionThatMatrixCannotConfirmAnswersBadGateway() {
    when(matrixSynapseService.syncModeratorPower(ROOM, Map.of(MEMBER, false), OWNER))
        .thenReturn(false);

    assertThatThrownBy(() -> service.reconcileOrFail(group(), MEMBER, ParticipantRole.PARTICIPANT))
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            failure -> assertThat(failure.getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
  }

  @Test
  void aChatWithoutRoomOrAMemberWithoutMatrixAccountHasNothingToDo() {
    var withoutRoom = group();
    withoutRoom.setMatrixRoomId(null);

    assertThat(service.reconcile(withoutRoom, MEMBER, ParticipantRole.CO_MODERATOR)).isTrue();
    assertThat(service.reconcile(group(), " ", ParticipantRole.CO_MODERATOR)).isTrue();
    service.reconcileOrFail(group(), null, ParticipantRole.PARTICIPANT);
    verify(matrixSynapseService, never()).syncModeratorPower(any(), any(), any());
  }

  @Test
  void theModeratorsSelectedAtCreationAreRaisedInOneGo() {
    when(matrixSynapseService.syncModeratorPower(
            ROOM, Map.of(MEMBER, true, "@second:oriso.test", true), OWNER))
        .thenReturn(true);

    assertThat(service.grantModerators(ROOM, OWNER, List.of(MEMBER, "@second:oriso.test", "")))
        .isTrue();
  }

  /** The room of the next occurrence of a recurring group: every moderator is raised there too. */
  @Test
  void everyModeratorOfTheGroupIsRaisedInAFreshOccurrenceRoom() {
    stubModerators();
    when(matrixSynapseService.moderatorLevelHolders("!next:oriso.test")).thenReturn(Set.of());
    when(matrixSynapseService.syncModeratorPower(
            "!next:oriso.test", Map.of(OWNER, true, MEMBER, true), OWNER))
        .thenReturn(true);

    assertThat(service.grantModeratorsOf(group(), "!next:oriso.test")).isTrue();
    verify(consultantRepository, never()).findById(any());
  }

  /**
   * A room prepared by a stop that failed earlier may still hold the level of someone demoted,
   * removed or gone since. It is taken back before the room becomes the group's current one.
   */
  @Test
  void aFormerModeratorLosesTheLevelInAPreparedOccurrenceRoom() {
    stubModerators();
    when(matrixSynapseService.moderatorLevelHolders("!next:oriso.test"))
        .thenReturn(Set.of(MEMBER, FORMER));
    when(matrixSynapseService.syncModeratorPower(
            "!next:oriso.test", Map.of(OWNER, true, MEMBER, true, FORMER, false), OWNER))
        .thenReturn(true);

    assertThat(service.grantModeratorsOf(group(), "!next:oriso.test")).isTrue();
  }

  @Test
  void aPreparedRoomThatKeepsAFormerModeratorsLevelFailsTheStop() {
    stubModerators();
    when(matrixSynapseService.moderatorLevelHolders("!next:oriso.test")).thenReturn(Set.of(FORMER));
    when(matrixSynapseService.syncModeratorPower(anyString(), anyMap(), anyString()))
        .thenReturn(false);

    assertThatThrownBy(() -> service.grantModeratorsOf(group(), "!next:oriso.test"))
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            failure -> assertThat(failure.getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
  }

  /** Without a former moderator to take back, a missing raise only withholds blurring. */
  @Test
  void aFailedRaiseInAFreshOccurrenceRoomIsReportedButNeverThrown() {
    stubModerators();
    when(matrixSynapseService.moderatorLevelHolders("!next:oriso.test")).thenReturn(Set.of());
    when(matrixSynapseService.syncModeratorPower(anyString(), anyMap(), anyString()))
        .thenReturn(false);

    assertThat(service.grantModeratorsOf(group(), "!next:oriso.test")).isFalse();
  }

  /** A promotion that rolls back must leave no Matrix moderator level behind. */
  @Test
  void aRaiseInsideATransactionWaitsForTheCommitAndNeverRunsOnRollback() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.grantModeratorsAfterCommit(group(), List.of(MEMBER));

      verify(matrixSynapseService, never()).syncModeratorPower(any(), any(), any());
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
      verify(matrixSynapseService, never()).syncModeratorPower(any(), any(), any());
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  /** After the commit only those who still moderate the group are raised. */
  @Test
  void aRaiseAfterTheCommitChecksTheRolesAgain() {
    stubModerators();
    when(matrixSynapseService.syncModeratorPower(ROOM, Map.of(MEMBER, true), OWNER))
        .thenReturn(true);
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.grantModeratorsAfterCommit(group(), Arrays.asList(MEMBER, FORMER, null));

      TransactionSynchronizationManager.getSynchronizations()
          .forEach(TransactionSynchronization::afterCommit);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    verify(matrixSynapseService).syncModeratorPower(ROOM, Map.of(MEMBER, true), OWNER);
  }

  @Test
  void aRaiseOutsideATransactionRunsAtOnce() {
    stubModerators();
    when(matrixSynapseService.syncModeratorPower(ROOM, Map.of(MEMBER, true), OWNER))
        .thenReturn(true);

    service.grantModeratorsAfterCommit(group(), List.of(MEMBER));

    verify(matrixSynapseService).syncModeratorPower(ROOM, Map.of(MEMBER, true), OWNER);
  }

  private void stubModerators() {
    when(participantRepository.findBySeriesId(7L))
        .thenReturn(
            List.of(
                participation("owner-id", ParticipantRole.OWNER),
                participation("comod-id", ParticipantRole.CO_MODERATOR),
                participation("participant-id", ParticipantRole.PARTICIPANT)));
    when(consultantRepository.findAllByIdIn(List.of("owner-id", "comod-id")))
        .thenReturn(List.of(consultant(OWNER), consultant(MEMBER)));
  }

  private static GroupChatParticipant participation(String consultantId, ParticipantRole role) {
    var participation = new GroupChatParticipant();
    participation.setConsultantId(consultantId);
    participation.setSeriesId(7L);
    participation.setRole(role);
    return participation;
  }

  private static Consultant consultant(String matrixUserId) {
    var consultant = new Consultant();
    consultant.setMatrixUserId(matrixUserId);
    return consultant;
  }
}
