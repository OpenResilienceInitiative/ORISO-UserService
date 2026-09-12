package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.AGENCY_DTO_KREUZBUND;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CHAT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateChatResponseDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.session.AgencySilentMembershipService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

/** Tests for Matrix group-chat creation through both public API versions. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateChatSimplifiedGroupChatFacadeTest {

  @InjectMocks private CreateChatFacade createChatFacade;

  @Mock private ChatService chatService;
  @Mock private SessionService sessionService;
  @Mock private AgencyService agencyService;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private GroupChatParticipantRepository groupChatParticipantRepository;
  @Mock private UserRepository userRepository;
  @Mock private AgencySilentMembershipService consultantMembership;

  @SuppressWarnings("unused")
  @Spy
  private ChatConverter chatConverter;

  private Consultant consultant;
  private Chat savedChat;
  private Session savedSession;

  @BeforeEach
  void setup() {
    when(consultantMembership.ensureMatrixAccount(any()))
        .thenAnswer(invocation -> ((Consultant) invocation.getArgument(0)).getMatrixUserId());
    when(consultantMembership.joinConsultantIntoRoom(any(), any(), any())).thenReturn(true);
    consultant = mock(Consultant.class);
    when(consultant.getId()).thenReturn("creator-consultant-id");
    when(consultant.getMatrixUserId()).thenReturn("@creator:matrix.org");
    when(consultant.getTenantId()).thenReturn(1L);

    savedChat = mock(Chat.class);
    when(savedChat.getId()).thenReturn(CHAT_ID);
    when(savedChat.getCreateDate()).thenReturn(LocalDateTime.now());
    when(chatService.saveChat(any())).thenReturn(savedChat);

    savedSession = mock(Session.class);
    when(savedSession.getId()).thenReturn(200L);
    when(savedSession.getCreateDate()).thenReturn(LocalDateTime.now());
    when(sessionService.saveSession(any())).thenReturn(savedSession);

    User systemUser = mock(User.class);
    when(userRepository.findByUserIdAndDeleteDateIsNull(any())).thenReturn(Optional.of(systemUser));

    when(agencyService.getAgency(any())).thenReturn(AGENCY_DTO_KREUZBUND);
    when(consultantRepository.findByIdAndDeleteDateIsNull(any())).thenReturn(Optional.empty());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = ConversationType.class,
      names = {"SELF_HELP", "INTERNAL_GROUP"})
  void groupSessionCarriesItsOwnerTenantBeforeTheFirstSave(ConversationType type) throws Exception {
    ChatDTO dto = chatDtoWithConsultantIds(List.of());
    Chat chat = new Chat();
    chat.setConversationType(type);
    doReturn(chat).when(chatConverter).convertToEntity(any(), any(), any());
    when(matrixSynapseService.createRoomAsMatrixUser(any(), any(), any()))
        .thenReturn(matrixRoomResponse("!tenant-regression:matrix.org"));
    when(matrixSynapseService.loginAsUserAccessToken(any())).thenReturn("test-token");
    createChatFacade.createChatV2(dto, consultant);
    var captured = ArgumentCaptor.forClass(Session.class);
    verify(sessionService, times(2)).saveSession(captured.capture());
    assertThat(captured.getAllValues().getFirst().getTenantId()).isEqualTo(1L);
  }

  private ChatDTO chatDtoWithConsultantIds(List<String> consultantIds) {
    ChatDTO dto = mock(ChatDTO.class);
    when(dto.getConsultantIds()).thenReturn(consultantIds);
    when(dto.getAgencyId()).thenReturn(1L);
    when(dto.getTopic()).thenReturn("Test Group Chat");
    return dto;
  }

  private ResponseEntity<MatrixCreateRoomResponseDTO> matrixRoomResponse(String roomId) {
    MatrixCreateRoomResponseDTO body = new MatrixCreateRoomResponseDTO();
    body.setRoomId(roomId);
    return ResponseEntity.ok(body);
  }

  // ---------------------------------------------------------------------------
  // Routing: V1 and V2 both delegate to simplified path when consultantIds set
  // ---------------------------------------------------------------------------

  @Test
  void createChatV1_Should_UseMatrixFlow_When_SelectionIsEmpty() throws Exception {
    ChatDTO chatDto = chatDtoWithConsultantIds(List.of());
    when(matrixSynapseService.createRoomAsMatrixUser(any(), any(), any()))
        .thenReturn(matrixRoomResponse("!room-id:matrix.org"));
    when(matrixSynapseService.loginAsUserAccessToken(any())).thenReturn("token-123");
    when(consultantRepository.findByIdAndDeleteDateIsNull("participant-1"))
        .thenReturn(Optional.empty());
    doReturn(mock(Chat.class)).when(chatConverter).convertToEntity(any(), any(), any());

    createChatFacade.createChatV1(chatDto, consultant);

    verify(matrixSynapseService).createRoomAsMatrixUser(any(), any(), any());
  }

  @Test
  void createChatV2_Should_UseMatrixFlow_When_SelectionIsEmpty() throws Exception {
    ChatDTO chatDto = chatDtoWithConsultantIds(List.of());
    when(matrixSynapseService.createRoomAsMatrixUser(any(), any(), any()))
        .thenReturn(matrixRoomResponse("!room-id:matrix.org"));
    when(matrixSynapseService.loginAsUserAccessToken(any())).thenReturn("token-123");
    when(consultantRepository.findByIdAndDeleteDateIsNull("participant-1"))
        .thenReturn(Optional.empty());
    doReturn(mock(Chat.class)).when(chatConverter).convertToEntity(any(), any(), any());

    createChatFacade.createChatV2(chatDto, consultant);

    verify(matrixSynapseService).createRoomAsMatrixUser(any(), any(), any());
  }

  // ---------------------------------------------------------------------------
  // createSimplifiedGroupChat — happy path
  // ---------------------------------------------------------------------------

  @Test
  void createSimplifiedGroupChat_Should_ReturnMatrixRoomIdAsGroupId() throws Exception {
    ChatDTO chatDto = chatDtoWithConsultantIds(List.of("participant-1"));
    String matrixRoomId = "!test-room:matrix.org";
    when(matrixSynapseService.createRoomAsMatrixUser(any(), any(), any()))
        .thenReturn(matrixRoomResponse(matrixRoomId));
    when(matrixSynapseService.loginAsUserAccessToken(any())).thenReturn("creator-token");
    Consultant participant = mock(Consultant.class);
    when(participant.getId()).thenReturn("participant-1");
    when(participant.getTenantId()).thenReturn(1L);
    when(participant.getMatrixUserId()).thenReturn("@participant:matrix.org");
    when(consultantRepository.findByIdAndDeleteDateIsNull("participant-1"))
        .thenReturn(Optional.of(participant));
    doReturn(mock(Chat.class)).when(chatConverter).convertToEntity(any(), any(), any());

    CreateChatResponseDTO result = createChatFacade.createChatV1(chatDto, consultant);

    assertThat(result.getMatrixRoomId()).isEqualTo(matrixRoomId);
  }

  @Test
  void createSimplifiedGroupChat_Should_PersistCreatorInGroupChatParticipantTable()
      throws Exception {
    ChatDTO chatDto = chatDtoWithConsultantIds(List.of());
    when(matrixSynapseService.createRoomAsMatrixUser(any(), any(), any()))
        .thenReturn(matrixRoomResponse("!room:matrix.org"));
    when(matrixSynapseService.loginAsUserAccessToken(any())).thenReturn("creator-token");
    doReturn(mock(Chat.class)).when(chatConverter).convertToEntity(any(), any(), any());

    createChatFacade.createChatV1(chatDto, consultant);

    // Creator saved once in group_chat_participant
    verify(groupChatParticipantRepository, times(1)).save(any());
  }

  @Test
  void createSimplifiedGroupChat_Should_SaveSessionAndChatWithMatrixRoomId() throws Exception {
    ChatDTO chatDto = chatDtoWithConsultantIds(List.of());
    when(matrixSynapseService.createRoomAsMatrixUser(any(), any(), any()))
        .thenReturn(matrixRoomResponse("!room:matrix.org"));
    when(matrixSynapseService.loginAsUserAccessToken(any())).thenReturn("creator-token");
    doReturn(mock(Chat.class)).when(chatConverter).convertToEntity(any(), any(), any());

    createChatFacade.createChatV1(chatDto, consultant);

    // sessionService.saveSession called twice: initial save + update with matrixRoomId
    verify(sessionService, times(2)).saveSession(any());
    // chatService.saveChat called twice: initial save + update with matrixRoomId
    verify(chatService, times(2)).saveChat(any());
  }

  @Test
  void createSimplifiedGroupChat_Should_RemainInactiveUntilCounsellorOpensOccurrence()
      throws Exception {
    ChatDTO chatDto = chatDtoWithConsultantIds(List.of());
    when(matrixSynapseService.createRoomAsMatrixUser(any(), any(), any()))
        .thenReturn(matrixRoomResponse("!room:matrix.org"));
    when(matrixSynapseService.loginAsUserAccessToken(any())).thenReturn("creator-token");
    Chat unsavedChat = mock(Chat.class);
    when(unsavedChat.getConversationType()).thenReturn(ConversationType.SELF_HELP);
    doReturn(unsavedChat).when(chatConverter).convertToEntity(any(), any(), any());

    createChatFacade.createChatV1(chatDto, consultant);

    verify(unsavedChat).setActive(false);
  }

  @Test
  void createSimplifiedGroupChatShouldOpenAnInternalTeamChatOnCreation() throws Exception {
    // #979: a team chat has no occurrence to open, so leaving it inactive dropped colleagues
    // into the askers' Waiting Area with a countdown to a start time nobody had chosen.
    ChatDTO chatDto = chatDtoWithConsultantIds(List.of());
    when(matrixSynapseService.createRoomAsMatrixUser(any(), any(), any()))
        .thenReturn(matrixRoomResponse("!room:matrix.org"));
    when(matrixSynapseService.loginAsUserAccessToken(any())).thenReturn("creator-token");
    Chat unsavedChat = mock(Chat.class);
    when(unsavedChat.getConversationType()).thenReturn(ConversationType.INTERNAL_GROUP);
    doReturn(unsavedChat).when(chatConverter).convertToEntity(any(), any(), any());

    createChatFacade.createChatV1(chatDto, consultant);

    verify(unsavedChat).setActive(true);
  }

  @Test
  void createSimplifiedGroupChatShouldStampBothRowsAsSelfHelpForOneOccurrenceSeries()
      throws Exception {
    ChatDTO chatDto = chatDtoWithConsultantIds(List.of());
    when(chatDto.getRepeatCount()).thenReturn(1);
    when(matrixSynapseService.createRoomAsMatrixUser(any(), any(), any()))
        .thenReturn(matrixRoomResponse("!room:matrix.org"));
    when(matrixSynapseService.loginAsUserAccessToken(any())).thenReturn("creator-token");

    createChatFacade.createChatV1(chatDto, consultant);

    ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
    ArgumentCaptor<Chat> chatCaptor = ArgumentCaptor.forClass(Chat.class);
    verify(sessionService, times(2)).saveSession(sessionCaptor.capture());
    verify(chatService, times(2)).saveChat(chatCaptor.capture());
    assertThat(sessionCaptor.getAllValues().get(0).getConversationType())
        .isEqualTo(ConversationType.SELF_HELP);
    assertThat(chatCaptor.getAllValues().get(0).getConversationType())
        .isEqualTo(ConversationType.SELF_HELP);
  }

  // ---------------------------------------------------------------------------
  // createSimplifiedGroupChat — consultant has no Matrix credentials
  // ---------------------------------------------------------------------------

  @Test
  void
      createSimplifiedGroupChat_Should_ThrowISE_And_Rollback_When_ConsultantMatrixProvisioningFails() {
    when(consultant.getMatrixUserId()).thenReturn(null);
    ChatDTO chatDto = chatDtoWithConsultantIds(List.of());
    doReturn(mock(Chat.class)).when(chatConverter).convertToEntity(any(), any(), any());

    assertThrows(
        InternalServerErrorException.class,
        () -> createChatFacade.createChatV1(chatDto, consultant));

    verify(sessionService).deleteSession(any());
    verify(chatService).deleteChat(any());
  }

  @Test
  void
      createSimplifiedGroupChat_Should_ThrowISE_And_Rollback_When_ConsultantMatrixProvisioningReturnsBlank()
          throws Exception {
    when(consultant.getMatrixUserId()).thenReturn("  ");
    ChatDTO chatDto = chatDtoWithConsultantIds(List.of());
    doReturn(mock(Chat.class)).when(chatConverter).convertToEntity(any(), any(), any());

    assertThrows(
        InternalServerErrorException.class,
        () -> createChatFacade.createChatV1(chatDto, consultant));

    verify(sessionService).deleteSession(any());
    verify(chatService).deleteChat(any());
  }

  @Test
  void createSimplifiedGroupChat_Should_ThrowISE_And_Rollback_When_MatrixRoomCreationFails()
      throws Exception {
    ChatDTO chatDto = chatDtoWithConsultantIds(List.of());
    when(matrixSynapseService.createRoomAsMatrixUser(any(), any(), any()))
        .thenThrow(new RuntimeException("Matrix unavailable"));
    doReturn(mock(Chat.class)).when(chatConverter).convertToEntity(any(), any(), any());

    assertThrows(
        InternalServerErrorException.class,
        () -> createChatFacade.createChatV1(chatDto, consultant));

    verify(sessionService).deleteSession(any());
    verify(chatService).deleteChat(any());
  }

  @Test
  void createSimplifiedGroupChat_Should_ThrowISE_And_Rollback_When_ConsultantTokenIsNull()
      throws Exception {
    ChatDTO chatDto = chatDtoWithConsultantIds(List.of());
    when(matrixSynapseService.createRoomAsMatrixUser(any(), any(), any()))
        .thenReturn(matrixRoomResponse("!room:matrix.org"));
    when(matrixSynapseService.loginAsUserAccessToken(any())).thenReturn(null);
    doReturn(mock(Chat.class)).when(chatConverter).convertToEntity(any(), any(), any());

    assertThrows(
        InternalServerErrorException.class,
        () -> createChatFacade.createChatV1(chatDto, consultant));

    verify(sessionService).deleteSession(any());
    verify(chatService).deleteChat(any());
  }

  // ---------------------------------------------------------------------------
  // createSimplifiedGroupChat — per-participant edge cases
  // ---------------------------------------------------------------------------

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "foreign,84,false",
    "deleted,1,true",
    "missing,1,false"
  })
  void creationRejectsInvalidSelectionBeforeAnyMatrixOrPersistenceSideEffect(
      String participantId, long tenantId, boolean deleted) throws Exception {
    var selected = new Consultant();
    selected.setId(participantId);
    selected.setTenantId(tenantId);
    if (deleted) selected.setDeleteDate(LocalDateTime.now());
    when(consultantRepository.findByIdAndDeleteDateIsNull(participantId))
        .thenReturn("missing".equals(participantId) ? Optional.empty() : Optional.of(selected));
    when(consultantRepository.findByIdAndDeleteDateIsNull(participantId))
        .thenReturn(
            deleted || "missing".equals(participantId) ? Optional.empty() : Optional.of(selected));
    when(matrixSynapseService.createRoomAsMatrixUser(any(), any(), any()))
        .thenReturn(matrixRoomResponse("!room:matrix.org"));
    when(matrixSynapseService.loginAsUserAccessToken(any())).thenReturn("creator-token");
    doReturn(mock(Chat.class)).when(chatConverter).convertToEntity(any(), any(), any());

    assertThrows(
        BadRequestException.class,
        () ->
            createChatFacade.createChatV2(
                chatDtoWithConsultantIds(List.of(participantId)), consultant));

    org.mockito.Mockito.verifyNoInteractions(
        matrixSynapseService,
        consultantMembership,
        sessionService,
        chatService,
        userRepository,
        groupChatParticipantRepository);
  }

  @Test
  void createSimplifiedGroupChat_Should_RejectParticipant_When_NotFoundInConsultantRepository()
      throws Exception {
    ChatDTO chatDto = chatDtoWithConsultantIds(List.of("unknown-participant"));
    when(matrixSynapseService.createRoomAsMatrixUser(any(), any(), any()))
        .thenReturn(matrixRoomResponse("!room:matrix.org"));
    when(matrixSynapseService.loginAsUserAccessToken(any())).thenReturn("creator-token");
    when(consultantRepository.findByIdAndDeleteDateIsNull("unknown-participant"))
        .thenReturn(Optional.empty());
    doReturn(mock(Chat.class)).when(chatConverter).convertToEntity(any(), any(), any());

    assertThrows(
        BadRequestException.class, () -> createChatFacade.createChatV1(chatDto, consultant));
    org.mockito.Mockito.verifyNoInteractions(
        matrixSynapseService, consultantMembership, groupChatParticipantRepository);
  }

  @Test
  void createSimplifiedGroupChat_Should_NotRecordParticipant_When_MembershipFails()
      throws Exception {
    ChatDTO chatDto = chatDtoWithConsultantIds(List.of("participant-1"));
    when(matrixSynapseService.createRoomAsMatrixUser(any(), any(), any()))
        .thenReturn(matrixRoomResponse("!room:matrix.org"));
    // creator gets token, participant does not
    when(matrixSynapseService.loginAsUserAccessToken("@creator:matrix.org"))
        .thenReturn("creator-token");
    Consultant participant = mock(Consultant.class);
    when(participant.getId()).thenReturn("participant-1");
    when(participant.getTenantId()).thenReturn(1L);
    when(participant.getMatrixUserId()).thenReturn("@participant:matrix.org");
    when(consultantMembership.joinConsultantIntoRoom(
            org.mockito.ArgumentMatchers.eq(participant), any(), any()))
        .thenReturn(false);
    when(consultantRepository.findByIdAndDeleteDateIsNull("participant-1"))
        .thenReturn(Optional.of(participant));
    doReturn(mock(Chat.class)).when(chatConverter).convertToEntity(any(), any(), any());

    assertThrows(
        InternalServerErrorException.class,
        () -> createChatFacade.createChatV1(chatDto, consultant));

    verify(consultantMembership)
        .joinConsultantIntoRoom(org.mockito.ArgumentMatchers.eq(participant), any(), any());
    // Only confirmed members are persisted.
    verify(groupChatParticipantRepository, times(1)).save(any());
  }

  @Test
  void createSimplifiedGroupChat_Should_ReportFailureAndRollback_When_OneInviteFails()
      throws Exception {
    ChatDTO chatDto = chatDtoWithConsultantIds(List.of("participant-1", "participant-2"));
    when(matrixSynapseService.createRoomAsMatrixUser(any(), any(), any()))
        .thenReturn(matrixRoomResponse("!room:matrix.org"));
    when(matrixSynapseService.loginAsUserAccessToken("@creator:matrix.org"))
        .thenReturn("creator-token");

    Consultant p1 = mock(Consultant.class);
    when(p1.getId()).thenReturn("participant-1");
    when(p1.getTenantId()).thenReturn(1L);
    when(p1.getMatrixUserId()).thenReturn("@p1:matrix.org");
    Consultant p2 = mock(Consultant.class);
    when(p2.getId()).thenReturn("participant-2");
    when(p2.getTenantId()).thenReturn(1L);
    when(p2.getMatrixUserId()).thenReturn("@p2:matrix.org");
    when(consultantRepository.findByIdAndDeleteDateIsNull("participant-1"))
        .thenReturn(Optional.of(p1));
    when(consultantRepository.findByIdAndDeleteDateIsNull("participant-2"))
        .thenReturn(Optional.of(p2));

    // p1 invite fails, p2 invite succeeds
    when(consultantMembership.joinConsultantIntoRoom(
            org.mockito.ArgumentMatchers.eq(p1), any(), any()))
        .thenThrow(new RuntimeException("Matrix error for p1"));
    when(matrixSynapseService.loginAsUserAccessToken("@p2:matrix.org")).thenReturn("p2-token");

    doReturn(mock(Chat.class)).when(chatConverter).convertToEntity(any(), any(), any());

    assertThrows(
        InternalServerErrorException.class,
        () -> createChatFacade.createChatV1(chatDto, consultant));
    verify(sessionService).deleteSession(savedSession);
    verify(chatService).deleteChat(savedChat);
  }

  // ---------------------------------------------------------------------------
  // resolveOrCreateGroupChatSystemUser — system user resolution
  // ---------------------------------------------------------------------------

  @Test
  void resolveOrCreateGroupChatSystemUser_Should_UseTenantScopedUser_When_Found() throws Exception {
    ChatDTO chatDto = chatDtoWithConsultantIds(List.of());
    when(matrixSynapseService.createRoomAsMatrixUser(any(), any(), any()))
        .thenReturn(matrixRoomResponse("!room:matrix.org"));
    when(matrixSynapseService.loginAsUserAccessToken(any())).thenReturn("token");
    doReturn(mock(Chat.class)).when(chatConverter).convertToEntity(any(), any(), any());

    User tenantUser = mock(User.class);
    when(userRepository.findByUserIdAndDeleteDateIsNull("group-chat-system-1"))
        .thenReturn(Optional.of(tenantUser));

    createChatFacade.createChatV1(chatDto, consultant);

    // Tenant-scoped lookup resolved on first call → generic lookup never needed
    verify(userRepository).findByUserIdAndDeleteDateIsNull("group-chat-system-1");
    verify(userRepository, never()).findByUserIdAndDeleteDateIsNull("group-chat-system");
  }

  @Test
  void resolveOrCreateGroupChatSystemUser_Should_FallBackToGenericUser_When_TenantUserNotFound()
      throws Exception {
    ChatDTO chatDto = chatDtoWithConsultantIds(List.of());
    when(matrixSynapseService.createRoomAsMatrixUser(any(), any(), any()))
        .thenReturn(matrixRoomResponse("!room:matrix.org"));
    when(matrixSynapseService.loginAsUserAccessToken(any())).thenReturn("token");
    doReturn(mock(Chat.class)).when(chatConverter).convertToEntity(any(), any(), any());

    User genericUser = mock(User.class);
    when(userRepository.findByUserIdAndDeleteDateIsNull("group-chat-system-1"))
        .thenReturn(Optional.empty());
    when(userRepository.findByUserIdAndDeleteDateIsNull("group-chat-system"))
        .thenReturn(Optional.of(genericUser));

    createChatFacade.createChatV1(chatDto, consultant);

    verify(userRepository).findByUserIdAndDeleteDateIsNull("group-chat-system");
  }

  @Test
  void resolveOrCreateGroupChatSystemUser_Should_CreateFallbackUser_When_NeitherFound()
      throws Exception {
    ChatDTO chatDto = chatDtoWithConsultantIds(List.of());
    when(matrixSynapseService.createRoomAsMatrixUser(any(), any(), any()))
        .thenReturn(matrixRoomResponse("!room:matrix.org"));
    when(matrixSynapseService.loginAsUserAccessToken(any())).thenReturn("token");
    doReturn(mock(Chat.class)).when(chatConverter).convertToEntity(any(), any(), any());

    when(userRepository.findByUserIdAndDeleteDateIsNull("group-chat-system-1"))
        .thenReturn(Optional.empty());
    when(userRepository.findByUserIdAndDeleteDateIsNull("group-chat-system"))
        .thenReturn(Optional.empty());
    User createdUser = mock(User.class);
    when(userRepository.save(any())).thenReturn(createdUser);

    createChatFacade.createChatV1(chatDto, consultant);

    verify(userRepository).save(any());
  }

  @Test
  void createSimplifiedGroupChatShouldPersistCreatorAsSeriesOwnerAndKeepLegacySessionId()
      throws Exception {
    ChatDTO chatDto = chatDtoWithConsultantIds(List.of());
    when(matrixSynapseService.createRoomAsMatrixUser(any(), any(), any()))
        .thenReturn(matrixRoomResponse("!room:matrix.org"));
    when(matrixSynapseService.loginAsUserAccessToken(any())).thenReturn("creator-token");
    doReturn(mock(Chat.class)).when(chatConverter).convertToEntity(any(), any(), any());

    createChatFacade.createChatV1(chatDto, consultant);

    ArgumentCaptor<GroupChatParticipant> captor =
        ArgumentCaptor.forClass(GroupChatParticipant.class);
    verify(groupChatParticipantRepository, times(1)).save(captor.capture());
    // creator participant must use sessionId (200L), not chatId (CHAT_ID=1L)
    assertThat(captor.getValue().getChatId()).isEqualTo(200L);
    assertThat(captor.getValue().getSeriesId()).isEqualTo(CHAT_ID);
    assertThat(captor.getValue().getRole()).isEqualTo(ParticipantRole.OWNER);
  }
}
