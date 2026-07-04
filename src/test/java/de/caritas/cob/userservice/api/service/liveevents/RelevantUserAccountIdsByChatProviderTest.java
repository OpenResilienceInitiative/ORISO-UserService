package de.caritas.cob.userservice.api.service.liveevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService.ResolvedRoomMember;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelevantUserAccountIdsByChatProviderTest {

  private static final String GROUP_ID = "!room:matrix.oriso.org";
  private static final String MATRIX_ROOM_ID = "!room:matrix.oriso.org";

  @InjectMocks private RelevantUserAccountIdsByChatProvider byChatProvider;

  @Mock private ChatRepository chatRepository;

  @Mock private GroupChatMembershipService groupChatMembershipService;

  private ResolvedRoomMember member(String accountId, boolean consultant) {
    return new ResolvedRoomMember(
        "@" + accountId + ":matrix.oriso.org", accountId, accountId, accountId, consultant);
  }

  @Test
  void collectUserIds_Should_ReturnAllAccountIds_When_MatrixRoomHasMembers() {
    var chat = new Chat();
    chat.setMatrixRoomId(MATRIX_ROOM_ID);
    when(chatRepository.findByGroupId(GROUP_ID)).thenReturn(Optional.of(chat));
    when(groupChatMembershipService.resolveMatrixRoomId(chat)).thenReturn(MATRIX_ROOM_ID);
    when(groupChatMembershipService.resolveHumanMembers(MATRIX_ROOM_ID))
        .thenReturn(
            List.of(member("consultant1", true), member("user1", false), member("user2", false)));

    List<String> collectedUserIds = byChatProvider.collectUserIds(GROUP_ID);

    assertThat(collectedUserIds, contains("consultant1", "user1", "user2"));
  }

  @Test
  void collectUserIds_Should_ReturnEmpty_When_RoomStateUnknown() {
    var chat = new Chat();
    chat.setMatrixRoomId(MATRIX_ROOM_ID);
    when(chatRepository.findByGroupId(GROUP_ID)).thenReturn(Optional.of(chat));
    when(groupChatMembershipService.resolveMatrixRoomId(chat)).thenReturn(MATRIX_ROOM_ID);
    when(groupChatMembershipService.resolveHumanMembers(MATRIX_ROOM_ID)).thenReturn(List.of());

    List<String> collectedUserIds = byChatProvider.collectUserIds(GROUP_ID);

    assertThat(collectedUserIds, empty());
  }

  @Test
  void collectUserIds_Should_FallBackToGroupId_When_ChatNotFound() {
    when(chatRepository.findByGroupId(GROUP_ID)).thenReturn(Optional.empty());
    when(groupChatMembershipService.resolveHumanMembers(GROUP_ID))
        .thenReturn(List.of(member("user1", false)));

    List<String> collectedUserIds = byChatProvider.collectUserIds(GROUP_ID);

    assertThat(collectedUserIds, contains("user1"));
  }

  @Test
  void collectUserIds_Should_ReturnEmpty_When_ChatFoundButNoMatrixRoom() {
    var chat = new Chat();
    when(chatRepository.findByGroupId("rcGroupId")).thenReturn(Optional.of(chat));
    when(groupChatMembershipService.resolveMatrixRoomId(chat)).thenReturn(null);
    when(groupChatMembershipService.resolveHumanMembers(any())).thenReturn(List.of());

    List<String> collectedUserIds = byChatProvider.collectUserIds("rcGroupId");

    assertThat(collectedUserIds, empty());
  }
}
