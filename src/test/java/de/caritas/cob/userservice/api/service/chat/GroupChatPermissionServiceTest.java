package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.ChatPermissionVerifier;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupChatPermissionServiceTest {

  @Mock private GroupChatParticipantRepository participantRepository;
  @Mock private ChatPermissionVerifier legacyPermissionVerifier;
  @Mock private Chat chat;
  @Mock private Consultant consultant;

  private GroupChatPermissionService service;

  @BeforeEach
  void setUp() {
    service = new GroupChatPermissionService(participantRepository, legacyPermissionVerifier);
    when(chat.getId()).thenReturn(42L);
  }

  @Test
  void ownerAndCoModeratorCanOpenOrEndAnOccurrence() {
    when(consultant.getId()).thenReturn("actor");
    for (ParticipantRole role : List.of(ParticipantRole.OWNER, ParticipantRole.CO_MODERATOR)) {
      when(participantRepository.findBySeriesId(42L))
          .thenReturn(List.of(participant("actor", role)));

      assertThatCode(() -> service.requireCanModerate(chat, consultant)).doesNotThrowAnyException();
    }
  }

  @Test
  void ordinaryParticipantCannotOpenOrEndAnOccurrenceEvenWithinAgency() {
    when(consultant.getId()).thenReturn("actor");
    when(participantRepository.findBySeriesId(42L))
        .thenReturn(List.of(participant("actor", ParticipantRole.PARTICIPANT)));
    assertThatThrownBy(() -> service.requireCanModerate(chat, consultant))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void legacyChatWithoutSeriesMembershipKeepsAgencyAuthorization() {
    when(participantRepository.findBySeriesId(42L)).thenReturn(List.of());
    when(legacyPermissionVerifier.hasSameAgencyAssigned(chat, consultant)).thenReturn(true);

    assertThatCode(() -> service.requireCanModerate(chat, consultant)).doesNotThrowAnyException();
  }

  private static GroupChatParticipant participant(String id, ParticipantRole role) {
    return GroupChatParticipant.builder().consultantId(id).role(role).build();
  }
}
