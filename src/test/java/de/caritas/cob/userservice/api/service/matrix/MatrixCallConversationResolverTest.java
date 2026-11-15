package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * US#1628: every group chat has a technical "shadow" session in the same Matrix room (see {@code
 * CreateChatFacade}). That pair is the group chat, not an ambiguous room. Any other session plus
 * chat in one room stays unresolved.
 */
@ExtendWith(MockitoExtension.class)
class MatrixCallConversationResolverTest {
  private static final String ROOM = "!group:synthetic.oriso.test";
  private static final long TENANT = 7L;

  @Mock private SessionRepository sessions;
  @Mock private ChatRepository chats;
  @InjectMocks private MatrixCallConversationResolver resolver;

  @ParameterizedTest
  @ValueSource(strings = {"group-chat-system", "group-chat-system-7"})
  void aChatWithItsShadowSessionResolvesToTheChat(String systemUserId) {
    stub(
        session(systemUserId, ConversationType.INTERNAL_GROUP, TENANT),
        chat(ConversationType.INTERNAL_GROUP, TENANT));

    for (var resolved : List.of(resolver.resolve(ROOM), resolver.resolveForWriting(ROOM))) {
      var conversation = resolved.orElseThrow();
      assertThat(conversation.getChatId()).isEqualTo(6L);
      assertThat(conversation.getSessionId()).isNull();
      assertThat(conversation.getTenantId()).isEqualTo(TENANT);
      assertThat(conversation.getMatrixRoomId()).isEqualTo(ROOM);
    }
  }

  @Test
  void aSelfHelpChatWithItsShadowSessionResolvesToTheChat() {
    stub(
        session("group-chat-system-7", ConversationType.SELF_HELP, TENANT),
        chat(ConversationType.SELF_HELP, TENANT));

    assertThat(resolver.resolveForWriting(ROOM).orElseThrow().getChatId()).isEqualTo(6L);
  }

  @Test
  void aRejectedShadowSessionDoesNotHideTheChat() {
    var shadow = session("group-chat-system-7", ConversationType.INTERNAL_GROUP, TENANT);
    shadow.setStatus(Session.SessionStatus.REJECTED);
    stub(shadow, chat(ConversationType.INTERNAL_GROUP, TENANT));

    assertThat(resolver.resolveForWriting(ROOM).orElseThrow().getChatId()).isEqualTo(6L);
  }

  @Test
  void aChatWithAnOrdinarySessionInTheSameRoomStaysUnresolved() {
    stub(
        session("asker-1", ConversationType.INTERNAL_GROUP, TENANT),
        chat(ConversationType.INTERNAL_GROUP, TENANT));

    assertThat(resolver.resolve(ROOM)).isEmpty();
    assertThat(resolver.resolveForWriting(ROOM)).isEmpty();
  }

  @Test
  void aShadowSessionOfAnotherTenantStaysUnresolved() {
    stub(
        session("group-chat-system-8", ConversationType.INTERNAL_GROUP, 8L),
        chat(ConversationType.INTERNAL_GROUP, TENANT));

    assertThat(resolver.resolve(ROOM)).isEmpty();
    assertThat(resolver.resolveForWriting(ROOM)).isEmpty();
  }

  @Test
  void aShadowSessionWithoutTenantStaysUnresolved() {
    stub(
        session("group-chat-system", ConversationType.INTERNAL_GROUP, null),
        chat(ConversationType.INTERNAL_GROUP, TENANT));

    assertThat(resolver.resolve(ROOM)).isEmpty();
  }

  @Test
  void aShadowSessionOfAnotherTypeStaysUnresolved() {
    stub(
        session("group-chat-system-7", ConversationType.SELF_HELP, TENANT),
        chat(ConversationType.INTERNAL_GROUP, TENANT));

    assertThat(resolver.resolve(ROOM)).isEmpty();
    assertThat(resolver.resolveForWriting(ROOM)).isEmpty();
  }

  @Test
  void aSystemSessionWithA1to1TypeStaysUnresolved() {
    stub(
        session("group-chat-system-7", ConversationType.AGENCY_COUNSELLING, TENANT),
        chat(ConversationType.INTERNAL_GROUP, TENANT));

    assertThat(resolver.resolve(ROOM)).isEmpty();
  }

  @Test
  void aSessionWithoutUserStaysUnresolved() {
    var session = session("group-chat-system-7", ConversationType.INTERNAL_GROUP, TENANT);
    session.setUser(null);
    stub(session, chat(ConversationType.INTERNAL_GROUP, TENANT));

    assertThat(resolver.resolve(ROOM)).isEmpty();
  }

  @Test
  void anOrdinarySessionAloneStillResolvesToTheSession() {
    when(sessions.findByMatrixRoomId(ROOM))
        .thenReturn(Optional.of(session("asker-1", ConversationType.AGENCY_COUNSELLING, TENANT)));
    when(chats.findByMatrixRoomId(ROOM)).thenReturn(Optional.empty());

    var conversation = resolver.resolve(ROOM).orElseThrow();
    assertThat(conversation.getSessionId()).isEqualTo(33L);
    assertThat(conversation.getChatId()).isNull();
  }

  private void stub(Session session, Chat chat) {
    when(sessions.findByMatrixRoomId(ROOM)).thenReturn(Optional.of(session));
    when(chats.findByMatrixRoomId(ROOM)).thenReturn(Optional.of(chat));
  }

  private static Session session(String userId, ConversationType type, Long tenant) {
    var user = new User(userId, null, userId, userId + "@synthetic.oriso.test", false);
    var session = new Session(user, 1, "00000", 1L, Session.SessionStatus.IN_PROGRESS, true);
    session.setId(33L);
    session.setTenantId(tenant);
    session.setMatrixRoomId(ROOM);
    session.setConversationType(type);
    return session;
  }

  private static Chat chat(ConversationType type, long ownerTenant) {
    var owner = new Consultant();
    owner.setId("owner");
    owner.setTenantId(ownerTenant);
    var start = java.time.LocalDateTime.of(2999, 1, 1, 12, 0);
    return Chat.builder()
        .topic("Synthetic group")
        .initialStartDate(start)
        .startDate(start)
        .id(6L)
        .chatOwner(owner)
        .matrixRoomId(ROOM)
        .conversationType(type)
        .build();
  }
}
