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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

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
    when(participantRepository.findBySeriesId(7L))
        .thenReturn(
            List.of(
                participation("owner-id", ParticipantRole.OWNER),
                participation("comod-id", ParticipantRole.CO_MODERATOR),
                participation("participant-id", ParticipantRole.PARTICIPANT)));
    when(consultantRepository.findById("owner-id")).thenReturn(Optional.of(consultant(OWNER)));
    when(consultantRepository.findById("comod-id")).thenReturn(Optional.of(consultant(MEMBER)));
    when(matrixSynapseService.syncModeratorPower(
            "!next:oriso.test", Map.of(OWNER, true, MEMBER, true), OWNER))
        .thenReturn(true);

    assertThat(service.grantModeratorsOf(group(), "!next:oriso.test")).isTrue();
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
