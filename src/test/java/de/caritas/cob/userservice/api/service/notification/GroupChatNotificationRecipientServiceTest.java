package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupChatNotificationRecipientServiceTest {

  @Mock private GroupChatParticipantRepository participantRepository;
  @Mock private UserChatRepository userChatRepository;
  @InjectMocks private GroupChatNotificationRecipientService service;

  @Test
  void returnsDistinctConsultantAndAskerIdentityIdsForTheSeries() {
    var series = mock(Chat.class);
    when(series.getId()).thenReturn(42L);
    var asker = mock(User.class);
    when(asker.getUserId()).thenReturn("asker-id");
    var sharedIdentity = mock(User.class);
    when(sharedIdentity.getUserId()).thenReturn("shared-id");
    when(participantRepository.findBySeriesId(42L))
        .thenReturn(
            List.of(
                GroupChatParticipant.builder().consultantId("owner-id").build(),
                GroupChatParticipant.builder().consultantId("shared-id").build()));
    when(userChatRepository.findByChat(series))
        .thenReturn(
            List.of(
                UserChat.builder().user(asker).chat(series).build(),
                UserChat.builder().user(sharedIdentity).chat(series).build()));

    assertThat(service.resolveRecipientIds(series))
        .containsExactly("owner-id", "shared-id", "asker-id");
  }

  @Test
  void ignoresIncompleteLegacyRelationsAndUnsavedSeries() {
    var series = mock(Chat.class);
    when(series.getId()).thenReturn(42L);
    when(participantRepository.findBySeriesId(42L))
        .thenReturn(
            List.of(
                GroupChatParticipant.builder().consultantId(null).build(),
                GroupChatParticipant.builder().consultantId(" ").build()));
    when(userChatRepository.findByChat(series))
        .thenReturn(List.of(UserChat.builder().user(null).chat(series).build()));

    assertThat(service.resolveRecipientIds(series)).isEmpty();
    assertThat(service.resolveRecipientIds(mock(Chat.class))).isEmpty();
    assertThat(service.resolveRecipientIds(null)).isEmpty();
  }
}
