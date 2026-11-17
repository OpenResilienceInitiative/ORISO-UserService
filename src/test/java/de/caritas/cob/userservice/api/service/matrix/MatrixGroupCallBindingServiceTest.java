package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService.CallRoomOrigin;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.MatrixCallObservation;
import de.caritas.cob.userservice.api.model.MatrixCallObservationEnd;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallObservationEndRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallObservationRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.chat.GroupChatConsultantAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * #1662: a group call's share link needs the call's binding. In an encrypted group the server never
 * reads the invite, so the counsellor who started the call registers it, and the server checks the
 * claim against Matrix and the group's own moderator rule. Nobody else gets a binding this way.
 */
class MatrixGroupCallBindingServiceTest {
  private static final String SOURCE = "!group:example";
  private static final String MEDIA = "!media:example";
  private static final String MODERATOR = "@moderator:example";
  private static final String PLAIN_MEMBER = "@member-counsellor:example";
  private static final String ASKER = "@asker:example";
  private static final long TENANT = 7L;
  private static final long CHAT = 45L;

  private final MatrixCallConversationResolver conversations =
      mock(MatrixCallConversationResolver.class);
  private final ChatRepository chats = mock(ChatRepository.class);
  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final MatrixCallBindingRepository bindings = mock(MatrixCallBindingRepository.class);
  private final MatrixCallBindingService callBindings = mock(MatrixCallBindingService.class);
  private final MatrixCallLifecycleService lifecycle = mock(MatrixCallLifecycleService.class);
  private final OrisoRoomRegistry rooms = mock(OrisoRoomRegistry.class);
  private final GroupChatConsultantAccess groupAccess = mock(GroupChatConsultantAccess.class);
  private final MatrixCallObservationRepository observations =
      mock(MatrixCallObservationRepository.class);
  private final MatrixCallObservationEndRepository ends =
      mock(MatrixCallObservationEndRepository.class);
  private final RecordingTransactionManager tx = new RecordingTransactionManager();
  private final List<String> matrixCallsInsideTransaction = new ArrayList<>();
  private final MatrixSynapseService matrix =
      mock(
          MatrixSynapseService.class,
          org.mockito.Mockito.withSettings()
              .invocationListeners(
                  report -> {
                    if (tx.isOpen())
                      matrixCallsInsideTransaction.add(report.getInvocation().toString());
                  }));

  private MatrixGroupCallBindingService service;
  private final Chat chat = new Chat();
  private final Consultant moderator = consultant("moderator-id", MODERATOR, TENANT);
  private final Consultant plainMember = consultant("member-id", PLAIN_MEMBER, TENANT);

  @BeforeEach
  void setUp() {
    service =
        new MatrixGroupCallBindingService(
            conversations,
            chats,
            consultants,
            users,
            bindings,
            callBindings,
            matrix,
            lifecycle,
            new DedicatedCallRoom(rooms, matrix),
            groupAccess,
            observations,
            ends,
            tx);
    chat.setId(CHAT);
    when(chats.findById(CHAT)).thenReturn(Optional.of(chat));
    when(conversations.resolveForWriting(SOURCE))
        .thenReturn(Optional.of(new MatrixCallConversation(SOURCE, TENANT, null, CHAT)));
    when(conversations.lockCurrentForWriting(any())).thenReturn(true);
    when(consultants.findByIdAndDeleteDateIsNull("moderator-id"))
        .thenReturn(Optional.of(moderator));
    when(consultants.findByIdAndDeleteDateIsNull("member-id")).thenReturn(Optional.of(plainMember));
    when(consultants.findAllByMatrixUserIdAndDeleteDateIsNull(MODERATOR))
        .thenReturn(List.of(moderator));
    when(consultants.findAllByMatrixUserIdAndDeleteDateIsNull(PLAIN_MEMBER))
        .thenReturn(List.of(plainMember));
    when(groupAccess.mayModerate(chat, moderator)).thenReturn(true);
    when(groupAccess.mayModerate(chat, plainMember)).thenReturn(false);
    var asker = new User();
    asker.setUserId("asker-id");
    asker.setMatrixUserId(ASKER);
    asker.setTenantId(TENANT);
    when(users.findAllByMatrixUserIdInAndDeleteDateIsNull(any()))
        .thenAnswer(
            call ->
                ((java.util.Collection<?>) call.getArgument(0)).contains(ASKER)
                    ? List.of(asker)
                    : List.of());
    when(consultants.findAllByMatrixUserIdInAndDeleteDateIsNull(any()))
        .thenReturn(List.of(plainMember));
    when(matrix.getCallRoomMembers(SOURCE))
        .thenReturn(Optional.of(List.of(MODERATOR, PLAIN_MEMBER, ASKER)));
    when(matrix.getCallRoomBinding(anyString(), anyString()))
        .thenReturn(Optional.of(Map.of("call_id", "call", "source_room_id", SOURCE)));
    when(matrix.getCallRoomOrigin(MEDIA, MODERATOR))
        .thenReturn(Optional.of(new CallRoomOrigin(MODERATOR, MODERATOR)));
    when(matrix.getCallRoomOrigin(MEDIA, PLAIN_MEMBER))
        .thenReturn(Optional.of(new CallRoomOrigin(PLAIN_MEMBER, PLAIN_MEMBER)));
    when(bindings.findBySourceRoomIdAndCallId(anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(bindings.findByMediaRoomId(anyString())).thenReturn(Optional.empty());
    when(callBindings.registerWhileSourceLocked(any())).thenReturn(true);
    when(lifecycle.isActiveRecipient(anyString())).thenReturn(true);
  }

  @Test
  void aModeratorWhoStartedTheCallGetsTheGroupBinding() {
    assertThat(service.bind(actor("moderator-id", true), SOURCE, "call", MEDIA, true)).isTrue();

    var captor = ArgumentCaptor.forClass(MatrixCallBinding.class);
    verify(callBindings).registerWhileSourceLocked(captor.capture());
    var binding = captor.getValue();
    assertThat(binding.getSourceRoomId()).isEqualTo(SOURCE);
    assertThat(binding.getCallId()).isEqualTo("call");
    assertThat(binding.getMediaRoomId()).isEqualTo(MEDIA);
    assertThat(binding.getCallerMatrixId()).isEqualTo(MODERATOR);
    assertThat(binding.getChatId()).isEqualTo(CHAT);
    assertThat(binding.getSessionId()).isNull();
    assertThat(binding.getTenantId()).isEqualTo(TENANT);
    assertThat(binding.isVideo()).isTrue();
    assertThat(binding.getInviteExpiresAt()).isGreaterThan(binding.getInvitedAt());
    assertThat(binding.getInvitedMatrixIds()).isEqualTo(Set.of(ASKER, PLAIN_MEMBER));
    // The lifecycle observes the call room, so the call (and its share link) ends with it.
    verify(matrix).ensureAdminInRoom(MEDIA, MODERATOR);
    // #1630: no Matrix call while the transaction is open.
    assertThat(matrixCallsInsideTransaction).isEmpty();
  }

  @Test
  void aCounsellorWhoIsNoMemberOfTheGroupRoomGetsNone() {
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(List.of(PLAIN_MEMBER, ASKER)));
    assertThat(service.bind(actor("moderator-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verifyNoInteractions(callBindings);
  }

  @Test
  void aPlainMemberCounsellorWhoMayNotModerateGetsNone() {
    // A member admitted from another Beratungsstelle or Träger may call, but not open it to guests.
    assertThat(service.bind(actor("member-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verifyNoInteractions(callBindings);
    verifyNoInteractions(matrix);
  }

  @Test
  void anAdviceSeekerGetsNone() {
    assertThat(service.bind(actor("asker-id", false), SOURCE, "call", MEDIA, true)).isFalse();
    verifyNoInteractions(callBindings);
    verifyNoInteractions(matrix);
  }

  @Test
  void aCounsellorOfAnotherTraegerGetsNone() {
    var foreign = actor("moderator-id", true);
    foreign.setTenantId(8L);
    assertThat(service.bind(foreign, SOURCE, "call", MEDIA, true)).isFalse();

    // Even with the right tenant claim, an account of another Träger is nobody here.
    moderator.setTenantId(8L);
    assertThat(service.bind(actor("moderator-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verifyNoInteractions(callBindings);
    verifyNoInteractions(matrix);
  }

  @Test
  void aMatrixIdHeldByTwoAccountsOfTheTraegerStandsForNobody() {
    var twin = consultant("twin-id", MODERATOR, TENANT);
    when(consultants.findAllByMatrixUserIdAndDeleteDateIsNull(MODERATOR))
        .thenReturn(List.of(moderator, twin));
    assertThat(service.bind(actor("moderator-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verifyNoInteractions(callBindings);
  }

  @Test
  void aSuspendedModeratorGetsNone() {
    when(lifecycle.isActiveRecipient("moderator-id")).thenReturn(false);
    assertThat(service.bind(actor("moderator-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verifyNoInteractions(callBindings);
    verifyNoInteractions(matrix);
  }

  @Test
  void aOneToOneSessionIsNotThisPath() {
    when(conversations.resolveForWriting(SOURCE))
        .thenReturn(Optional.of(new MatrixCallConversation(SOURCE, TENANT, 16L, null)));
    assertThat(service.bind(actor("moderator-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verifyNoInteractions(callBindings);
  }

  @Test
  void aCallRoomSomebodyElseCreatedIsRefused() {
    when(matrix.getCallRoomOrigin(MEDIA, MODERATOR))
        .thenReturn(Optional.of(new CallRoomOrigin(ASKER, MODERATOR)));
    assertThat(service.bind(actor("moderator-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verifyNoInteractions(callBindings);
  }

  @Test
  void aCallRoomBoundToAnotherCallIsRefused() {
    when(matrix.getCallRoomBinding(MEDIA, MODERATOR))
        .thenReturn(Optional.of(Map.of("call_id", "other", "source_room_id", SOURCE)));
    assertThat(service.bind(actor("moderator-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verifyNoInteractions(callBindings);
  }

  @Test
  void aConversationRoomIsNeverACallRoom() {
    when(rooms.isKnownConversationRoom(MEDIA)).thenReturn(true);
    assertThat(service.bind(actor("moderator-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    assertThat(service.bind(actor("moderator-id", true), SOURCE, "call", SOURCE, true)).isFalse();
    verifyNoInteractions(callBindings);
  }

  @Test
  void aCallTheModeratorAlreadyEndedIsNeverBound() {
    when(ends.findForUpdateBySourceRoomIdAndCallIdAndActorMatrixId(SOURCE, "call", MODERATOR))
        .thenReturn(Optional.of(new MatrixCallObservationEnd()));
    assertThat(service.bind(actor("moderator-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verify(callBindings, never()).registerWhileSourceLocked(any());
    verify(matrix, never()).ensureAdminInRoom(anyString(), anyString());
  }

  @Test
  void aCallWhoseOwnRegistrationEndedIsNeverBound() {
    var ended =
        MatrixCallObservation.builder()
            .sourceRoomId(SOURCE)
            .callId("call")
            .mediaRoomId(MEDIA)
            .creatorMatrixId(MODERATOR)
            .tenantId(TENANT)
            .chatId(CHAT)
            .expiresAt(System.currentTimeMillis() + 60_000)
            .endedAt(System.currentTimeMillis())
            .build();
    when(observations.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(ended));
    assertThat(service.bind(actor("moderator-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verify(callBindings, never()).registerWhileSourceLocked(any());
  }

  @Test
  void theModeratorsRightIsCheckedAgainInsideTheTransaction() {
    // Demoted while Matrix was asked.
    when(groupAccess.mayModerate(chat, moderator)).thenReturn(true, false);
    assertThat(service.bind(actor("moderator-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verify(callBindings, never()).registerWhileSourceLocked(any());
  }

  @Test
  void aRenewalConfirmsTheSameLiveBinding() {
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(existing(MEDIA, MODERATOR)));
    assertThat(service.bind(actor("moderator-id", true), SOURCE, "call", MEDIA, true)).isTrue();
    verify(callBindings, never()).registerWhileSourceLocked(any());
    verifyNoInteractions(matrix);
  }

  @Test
  void anExistingBindingOfSomebodyElseOrAnotherRoomIsNeverReplaced() {
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(existing(MEDIA, ASKER)));
    assertThat(service.bind(actor("moderator-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(existing("!other-media:example", MODERATOR)));
    assertThat(service.bind(actor("moderator-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verify(callBindings, never()).registerWhileSourceLocked(any());
  }

  @Test
  void anEndedBindingIsNeverConfirmed() {
    long now = System.currentTimeMillis();
    var ended =
        MatrixCallBinding.builder()
            .id(5L)
            .sourceRoomId(SOURCE)
            .callId("call")
            .mediaRoomId(MEDIA)
            .callerMatrixId(MODERATOR)
            .chatId(CHAT)
            .tenantId(TENANT)
            .invitedAt(now)
            .inviteExpiresAt(now + 60_000)
            .endedAt(now)
            .build();
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(ended));
    assertThat(service.bind(actor("moderator-id", true), SOURCE, "call", MEDIA, true)).isFalse();
  }

  @Test
  void aBindingThatAppearedMeanwhileIsConfirmedOnlyWhenItIsTheSameCall() {
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.empty(), Optional.of(existing(MEDIA, ASKER)));
    assertThat(service.bind(actor("moderator-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verify(callBindings, never()).registerWhileSourceLocked(any());
  }

  @Test
  void aCallRoomAlreadyBoundElsewhereIsRefused() {
    when(bindings.findByMediaRoomId(MEDIA)).thenReturn(Optional.of(existing(MEDIA, MODERATOR)));
    assertThat(service.bind(actor("moderator-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verify(callBindings, never()).registerWhileSourceLocked(any());
  }

  private static MatrixCallBinding existing(String media, String caller) {
    long now = System.currentTimeMillis();
    return MatrixCallBinding.builder()
        .id(5L)
        .sourceRoomId(SOURCE)
        .callId("call")
        .mediaRoomId(media)
        .callerMatrixId(caller)
        .chatId(CHAT)
        .tenantId(TENANT)
        .invitedAt(now)
        .inviteExpiresAt(now + 60_000)
        .build();
  }

  private static AuthenticatedUser actor(String id, boolean consultant) {
    var actor = new AuthenticatedUser();
    actor.setUserId(id);
    actor.setTenantId(TENANT);
    actor.setRoles(Set.of(consultant ? "consultant" : "user"));
    return actor;
  }

  private static Consultant consultant(String id, String matrixId, long tenant) {
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setMatrixUserId(matrixId);
    consultant.setTenantId(tenant);
    return consultant;
  }
}
