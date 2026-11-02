package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** ORISO-UserService#1510: who of the people a message names gets a marked row. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageMentionNotificationServiceTest {

  private static final String ROOM = "!room:oriso";
  private static final String COLLEAGUE = "colleague-id";
  private static final String COLLEAGUE_MATRIX = "@colleague:oriso";
  private static final PrivacyEnvelope ENVELOPE =
      PrivacyEnvelope.builder().messageId("$event-1").build();

  @InjectMocks private MessageMentionNotificationService service;
  @Mock private ConsultantRepository consultants;
  @Mock private MatrixSynapseService matrix;
  @Mock private AccountInactivityService accountLifecycle;
  @Mock private TeamDiscussionNotificationService conversationLevels;
  @Mock private EventNotificationService eventNotifications;

  @BeforeEach
  void setUp() {
    when(matrix.getRoomMembers(ROOM)).thenReturn(Optional.of(List.of(COLLEAGUE_MATRIX)));
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull(COLLEAGUE_MATRIX))
        .thenReturn(Optional.of(consultant(COLLEAGUE)));
    when(accountLifecycle.inactiveAmong(any())).thenReturn(Set.of());
  }

  private static Consultant consultant(String id) {
    var consultant = new Consultant();
    consultant.setId(id);
    return consultant;
  }

  private void mention(String... matrixUserIds) {
    service.notifyMentioned(ROOM, "sender-id", List.of(matrixUserIds), null, ENVELOPE);
  }

  @Test
  void notifyMentioned_Should_MarkTheColleagueInTheRoom() {
    mention(COLLEAGUE_MATRIX);

    verify(eventNotifications)
        .createMentionNotifications(ROOM, "sender-id", null, ENVELOPE, Set.of(COLLEAGUE));
  }

  /** The list is the client's claim: a forged id must not reveal the conversation to anyone. */
  @Test
  void notifyMentioned_Should_IgnoreSomeoneOutsideTheRoom() {
    mention("@outsider:oriso");

    verifyNoInteractions(eventNotifications);
  }

  @Test
  void notifyMentioned_Should_MarkNobody_When_TheMembersAreUnknown() {
    when(matrix.getRoomMembers(ROOM)).thenReturn(Optional.empty());

    mention(COLLEAGUE_MATRIX);

    verifyNoInteractions(eventNotifications);
  }

  /** The advice seeker is in the room too, but no counsellor. */
  @Test
  void notifyMentioned_Should_IgnoreARoomMemberWhoIsNoCounsellor() {
    when(matrix.getRoomMembers(ROOM)).thenReturn(Optional.of(List.of("@asker:oriso")));
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull("@asker:oriso"))
        .thenReturn(Optional.empty());

    mention("@asker:oriso");

    verifyNoInteractions(eventNotifications);
  }

  @Test
  void notifyMentioned_Should_NotTellTheSenderAboutTheirOwnMention() {
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull(COLLEAGUE_MATRIX))
        .thenReturn(Optional.of(consultant("sender-id")));

    mention(COLLEAGUE_MATRIX);

    verifyNoInteractions(eventNotifications);
  }

  @Test
  void notifyMentioned_Should_SkipAnInactiveAccount() {
    when(accountLifecycle.inactiveAmong(any())).thenReturn(Set.of(COLLEAGUE));

    mention(COLLEAGUE_MATRIX);

    verifyNoInteractions(eventNotifications);
  }

  @Test
  void notifyMentioned_Should_RespectAMutedConversation() {
    when(conversationLevels.dropForConversationLevel(COLLEAGUE, ROOM, true)).thenReturn(true);

    mention(COLLEAGUE_MATRIX);

    verifyNoInteractions(eventNotifications);
  }

  @Test
  void notifyMentioned_Should_DoNothing_When_NobodyIsMentioned() {
    service.notifyMentioned(ROOM, "sender-id", List.of(), null, ENVELOPE);
    service.notifyMentioned(ROOM, "sender-id", null, null, ENVELOPE);

    verifyNoInteractions(matrix, eventNotifications);
  }

  @Test
  void notifyMentioned_Should_NeverFailTheMessageEvent() {
    when(matrix.getRoomMembers(ROOM)).thenThrow(new IllegalStateException("matrix down"));

    assertThatCode(() -> mention(COLLEAGUE_MATRIX)).doesNotThrowAnyException();
    verify(eventNotifications, org.mockito.Mockito.never())
        .createMentionNotifications(any(), any(), any(), any(), eq(Set.of(COLLEAGUE)));
  }
}
