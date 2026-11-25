package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.matrix.GroupModeratorPowerService;
import java.time.LocalDateTime;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ORISO-Frontend#2229: a demoted, removed or leaving co-moderator must not keep the Matrix
 * moderator level. When Matrix cannot take it back, the whole change rolls back in the database:
 * the person keeps their role and membership and the change can be repeated. Runs the real, proxied
 * service against the database; only Matrix is replaced.
 */
@SpringBootTest
@ActiveProfiles("testing")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GroupChatModeratorDemotionRollbackIT {

  private static final String ROOM = "!demotion-rollback:matrix.test";

  @Autowired private GroupChatRoleService roleService;
  @Autowired private ChatRepository chats;
  @Autowired private ConsultantRepository consultants;
  @Autowired private GroupChatParticipantRepository participants;

  @MockitoBean private GroupModeratorPowerService moderatorPower;
  @MockitoBean private GroupChatMembershipService membership;

  private Chat series;
  private Consultant owner;
  private Consultant coModerator;

  @BeforeEach
  void setUp() {
    var users =
        StreamSupport.stream(consultants.findAll().spliterator(), false)
            .filter(user -> user.getDeleteDate() == null)
            .filter(user -> user.getMatrixUserId() != null && !user.getMatrixUserId().isBlank())
            .limit(2)
            .toList();
    assertThat(users).hasSize(2);
    owner = users.get(0);
    coModerator = users.get(1);
    var now = LocalDateTime.now();
    series =
        chats.save(
            Chat.builder()
                .topic("Demotion rollback group")
                .consultingTypeId(1)
                .initialStartDate(now)
                .startDate(now)
                .duration(60)
                .conversationType(ConversationType.SELF_HELP)
                .matrixRoomId(ROOM)
                .chatOwner(owner)
                .createDate(now)
                .updateDate(now)
                .build());
    participants.save(participation(owner, ParticipantRole.OWNER));
    participants.save(participation(coModerator, ParticipantRole.CO_MODERATOR));
    doThrow(
            new CustomValidationHttpStatusException(
                HttpStatusExceptionReason.INTERNAL_CHAT_MEMBERSHIP_FAILED, HttpStatus.BAD_GATEWAY))
        .when(moderatorPower)
        .reconcileOrFail(any(), any(), any());
  }

  @AfterEach
  void cleanUp() {
    if (series != null) {
      participants.findBySeriesId(series.getId()).forEach(participants::delete);
      chats.delete(series);
    }
  }

  @Test
  void aDemotionThatMatrixCannotConfirmKeepsTheCoModeratorRole() {
    assertThatThrownBy(
            () ->
                roleService.changeRole(
                    series.getId(),
                    owner.getId(),
                    coModerator.getId(),
                    ParticipantRole.PARTICIPANT))
        .isInstanceOf(CustomValidationHttpStatusException.class);

    assertThat(roleOfCoModerator()).isEqualTo(ParticipantRole.CO_MODERATOR);
  }

  @Test
  void aRemovalThatMatrixCannotConfirmKeepsTheCoModeratorInTheGroup() {
    assertThatThrownBy(
            () -> roleService.removeParticipant(series.getId(), owner.getId(), coModerator.getId()))
        .isInstanceOf(CustomValidationHttpStatusException.class);

    assertThat(roleOfCoModerator()).isEqualTo(ParticipantRole.CO_MODERATOR);
  }

  @Test
  void aLeaveThatMatrixCannotConfirmKeepsTheCoModeratorInTheGroup() {
    var current = chats.findById(series.getId()).orElseThrow();

    assertThatThrownBy(() -> roleService.leaveSeries(current, coModerator))
        .isInstanceOf(CustomValidationHttpStatusException.class);

    assertThat(roleOfCoModerator()).isEqualTo(ParticipantRole.CO_MODERATOR);
  }

  /** Read outside the failed transaction: what the database holds after the rollback. */
  private ParticipantRole roleOfCoModerator() {
    return participants
        .findBySeriesIdAndConsultantId(series.getId(), coModerator.getId())
        .map(GroupChatParticipant::getRole)
        .orElse(null);
  }

  private GroupChatParticipant participation(Consultant consultant, ParticipantRole role) {
    return GroupChatParticipant.builder()
        .chatId(992L)
        .seriesId(series.getId())
        .consultantId(consultant.getId())
        .role(role)
        .build();
  }
}
