package de.caritas.cob.userservice.api.service;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.ACTIVE_CHAT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.AUTHENTICATED_USER;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.AUTHENTICATED_USER_3;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.AUTHENTICATED_USER_CONSULTANT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CHAT_DTO;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CHAT_DURATION;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CHAT_HINT_MESSAGE;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CHAT_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CHAT_ID_3;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CHAT_START_DATE;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CHAT_START_TIME;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CHAT_TOPIC;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CHAT_V2;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.INACTIVE_CHAT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.MATRIX_ROOM_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_ID;
import static java.util.Collections.singletonList;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionResponseDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Chat.ChatInterval;
import de.caritas.cob.userservice.api.model.Chat.ChatModality;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.port.out.ChatAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.chat.GroupChatParticipantReconciliationService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

  @InjectMocks private ChatService chatService;

  @Mock private ChatRepository chatRepository;

  @Mock private ChatAgencyRepository chatAgencyRepository;

  @Mock private UserChatRepository chatUserRepository;

  @Mock private ConsultantService consultantService;

  @Mock private GroupChatParticipantRepository groupChatParticipantRepository;

  @Mock private GroupChatParticipantReconciliationService participantReconciliationService;

  @Mock private AgencyService agencyService;

  private static final long LOCAL_CHAT_AGENCY_ID = 1L;

  @BeforeEach
  void stubChatAgencyRepository() {
    lenient()
        .when(chatAgencyRepository.findByChat_IdIn(Mockito.anySet()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Set<Long> chatIds = invocation.getArgument(0);
              List<ChatAgency> agencies = new ArrayList<>();
              if (chatIds.contains(ACTIVE_CHAT.getId())) {
                agencies.add(chatAgencyFor(ACTIVE_CHAT.getId(), LOCAL_CHAT_AGENCY_ID));
              }
              if (chatIds.contains(CHAT_ID_3)) {
                agencies.add(chatAgencyFor(CHAT_ID_3, LOCAL_CHAT_AGENCY_ID));
              }
              return agencies;
            });
    lenient()
        .when(chatAgencyRepository.findByChat_Id(Mockito.anyLong()))
        .thenAnswer(
            invocation -> {
              Long chatId = invocation.getArgument(0);
              if (ACTIVE_CHAT.getId().equals(chatId)) {
                return List.of(chatAgencyFor(chatId, LOCAL_CHAT_AGENCY_ID));
              }
              if (CHAT_ID_3.equals(chatId)) {
                return List.of(chatAgencyFor(chatId, LOCAL_CHAT_AGENCY_ID));
              }
              return List.of();
            });
  }

  private ChatAgency chatAgencyFor(Long chatId, Long agencyId) {
    Chat chat = Mockito.mock(Chat.class);
    Mockito.when(chat.getId()).thenReturn(chatId);
    return new ChatAgency(chat, agencyId);
  }

  /**
   * Returns a fresh {@link Chat} that mirrors the shared {@code ACTIVE_CHAT} fixture but, unlike
   * it, carries a non-null {@code chatAgencies} set. Production {@code ChatService#createUserChat}
   * dereferences {@code chat.getChatAgencies()}; the shared {@code ACTIVE_CHAT} constant is built
   * without agencies (so it would NPE) and is referenced by 13 test classes, hence it must not be
   * mutated. Building a local copy keeps the change scoped to this test.
   */
  private Chat activeChatWithAgency() {
    return Chat.builder()
        .id(ACTIVE_CHAT.getId())
        .topic(ACTIVE_CHAT.getTopic())
        .consultingTypeId(ACTIVE_CHAT.getConsultingTypeId())
        .initialStartDate(ACTIVE_CHAT.getInitialStartDate())
        .startDate(ACTIVE_CHAT.getStartDate())
        .duration(ACTIVE_CHAT.getDuration())
        .repetitive(ACTIVE_CHAT.isRepetitive())
        .conversationType(de.caritas.cob.userservice.api.model.ConversationType.SELF_HELP)
        .chatInterval(ACTIVE_CHAT.getChatInterval())
        .active(ACTIVE_CHAT.isActive())
        .maxParticipants(ACTIVE_CHAT.getMaxParticipants())
        .matrixRoomId(ACTIVE_CHAT.getMatrixRoomId())
        .chatOwner(ACTIVE_CHAT.getChatOwner())
        .chatUsers(ACTIVE_CHAT.getChatUsers())
        .chatAgencies(Set.of(new ChatAgency(null, LOCAL_CHAT_AGENCY_ID)))
        .updateDate(ACTIVE_CHAT.getUpdateDate())
        .createDate(ACTIVE_CHAT.getCreateDate())
        .build();
  }

  @Test
  void getChatsForUserId_Should_CallFindByUserIdAndFindAssignedByUserIdOnChatRepository() {
    chatService.getChatsForUserId(USER_ID);

    verify(chatRepository).findByUserId(USER_ID);
    verify(chatRepository).findAssignedByUserId(USER_ID);
  }

  @Test
  void getChatsForUserId_Should_ConcatChatsAndAssignedChats() {
    when(chatRepository.findByUserId(USER_ID)).thenReturn(singletonList(activeChatWithAgency()));
    when(chatRepository.findAssignedByUserId(USER_ID)).thenReturn(singletonList(CHAT_V2));

    List<UserSessionResponseDTO> resultList = chatService.getChatsForUserId(USER_ID);

    assertEquals(2, resultList.size());
  }

  @Test
  void getChatsForUserId_Should_NotExposeNewSeriesBeforeExplicitDeepLinkJoin() {
    Chat series = activeChatWithAgency();
    when(chatRepository.findByUserId(USER_ID)).thenReturn(List.of(series));
    when(groupChatParticipantRepository.findBySeriesId(series.getId()))
        .thenReturn(List.of(GroupChatParticipant.builder().consultantId("owner").build()));

    List<UserSessionResponseDTO> result = chatService.getChatsForUserId(USER_ID);

    assertThat(result, hasSize(0));
  }

  @Test
  void getChatsForUserId_Should_ReturnListOfUserSessionResponseDTOWithChats() {
    when(chatRepository.findByUserId(USER_ID)).thenReturn(singletonList(activeChatWithAgency()));
    when(consultantService.findConsultantsByAgencyIds(Mockito.any()))
        .thenReturn(singletonList(CONSULTANT));

    List<UserSessionResponseDTO> resultList = chatService.getChatsForUserId(USER_ID);

    assertNull(resultList.get(0).getSession());
    assertNotNull(resultList.get(0).getChat());
    assertEquals(ACTIVE_CHAT.getId(), resultList.get(0).getChat().getId());
    assertEquals(ACTIVE_CHAT.getTopic(), resultList.get(0).getChat().getTopic());
    assertThat(
        ACTIVE_CHAT.getConsultingTypeId(), is(resultList.get(0).getChat().getConsultingType()));
    assertEquals(
        LocalDate.of(
            ACTIVE_CHAT.getStartDate().getYear(),
            ACTIVE_CHAT.getStartDate().getMonth(),
            ACTIVE_CHAT.getStartDate().getDayOfMonth()),
        resultList.get(0).getChat().getStartDate());
    assertEquals(
        LocalTime.of(
            ACTIVE_CHAT.getInitialStartDate().getHour(),
            ACTIVE_CHAT.getInitialStartDate().getMinute()),
        resultList.get(0).getChat().getStartTime());
    assertEquals(ACTIVE_CHAT.getDuration(), resultList.get(0).getChat().getDuration());
    assertEquals(ACTIVE_CHAT.isRepetitive(), resultList.get(0).getChat().isRepetitive());
    assertEquals(ACTIVE_CHAT.isActive(), resultList.get(0).getChat().isActive());
    assertEquals(ACTIVE_CHAT.getMatrixRoomId(), resultList.get(0).getChat().getMatrixRoomId());
    assertEquals(ACTIVE_CHAT.getRepeatCount(), resultList.get(0).getChat().getRepeatCount());
    assertEquals(
        ACTIVE_CHAT.getCurrentOccurrenceIndex(),
        resultList.get(0).getChat().getCurrentOccurrenceIndex());
    assertEquals(ACTIVE_CHAT.getChatInterval(), resultList.get(0).getChat().getChatInterval());
    assertEquals(ACTIVE_CHAT.getChatModality(), resultList.get(0).getChat().getModality());
    assertEquals(ACTIVE_CHAT.getTimezone(), resultList.get(0).getChat().getTimezone());
    assertNotNull(resultList.get(0).getChat().getModerators());
    assertEquals(1, resultList.get(0).getChat().getModerators().length);
    assertEquals(CONSULTANT.getMatrixUserId(), resultList.get(0).getChat().getModerators()[0]);
  }

  @Test
  void getChatsForUserId_Should_ExposeOnlyExplicitSeriesModeratorsForNewSeries() {
    Chat series = activeChatWithAgency();
    when(chatRepository.findAssignedByUserId(USER_ID)).thenReturn(List.of(series));
    when(groupChatParticipantRepository.findBySeriesId(series.getId()))
        .thenReturn(
            List.of(
                GroupChatParticipant.builder()
                    .consultantId("owner")
                    .role(ParticipantRole.OWNER)
                    .build(),
                GroupChatParticipant.builder()
                    .consultantId("co-mod")
                    .role(ParticipantRole.CO_MODERATOR)
                    .build(),
                GroupChatParticipant.builder()
                    .consultantId("participant")
                    .role(ParticipantRole.PARTICIPANT)
                    .build()));

    List<UserSessionResponseDTO> result = chatService.getChatsForUserId(USER_ID);

    assertArrayEquals(new String[] {"owner", "co-mod"}, result.get(0).getChat().getModerators());
  }

  @Test
  void getChatsForUserId_Should_ExposeExplicitParticipantRolesAndDisplayNames() {
    Chat series = activeChatWithAgency();
    when(chatRepository.findAssignedByUserId(USER_ID)).thenReturn(List.of(series));
    when(groupChatParticipantRepository.findBySeriesId(series.getId()))
        .thenReturn(
            List.of(
                GroupChatParticipant.builder()
                    .consultantId("owner")
                    .role(ParticipantRole.OWNER)
                    .build()));
    Consultant owner = Mockito.mock(Consultant.class);
    when(owner.getDisplayName()).thenReturn("Alice Owner");
    when(consultantService.getConsultant("owner")).thenReturn(Optional.of(owner));

    List<UserSessionResponseDTO> result = chatService.getChatsForUserId(USER_ID);

    assertThat(result.get(0).getChat().getParticipants(), hasSize(1));
    assertEquals("owner", result.get(0).getChat().getParticipants().get(0).getConsultantId());
    assertEquals("OWNER", result.get(0).getChat().getParticipants().get(0).getRole().getValue());
    assertEquals("Alice Owner", result.get(0).getChat().getParticipants().get(0).getDisplayName());
  }

  @Test
  void getChatsForUserId_Should_FallBackToFirstAndLastName_WhenDisplayNameIsEmpty() {
    Chat series = activeChatWithAgency();
    when(chatRepository.findAssignedByUserId(USER_ID)).thenReturn(List.of(series));
    when(groupChatParticipantRepository.findBySeriesId(series.getId()))
        .thenReturn(
            List.of(
                GroupChatParticipant.builder()
                    .consultantId("co-moderator")
                    .role(ParticipantRole.CO_MODERATOR)
                    .build()));
    Consultant coModerator = Mockito.mock(Consultant.class);
    when(coModerator.getFirstName()).thenReturn("Carlo");
    when(coModerator.getLastName()).thenReturn("Co-Moderator");
    when(consultantService.getConsultant("co-moderator")).thenReturn(Optional.of(coModerator));

    List<UserSessionResponseDTO> result = chatService.getChatsForUserId(USER_ID);

    assertEquals(
        "Carlo Co-Moderator", result.get(0).getChat().getParticipants().get(0).getDisplayName());
  }

  @Test
  void
      getChatsForUserId_Should_ReturnListOfUserSessionResponseDTOWithChats_When_AssignedChatIsFound() {
    when(chatRepository.findAssignedByUserId(USER_ID)).thenReturn(singletonList(CHAT_V2));
    when(consultantService.findConsultantsByAgencyIds(Mockito.any()))
        .thenReturn(singletonList(CONSULTANT));
    when(agencyService.getAgency(
            CHAT_V2.getChatAgencies().stream().findFirst().orElseThrow().getAgencyId()))
        .thenReturn(new AgencyDTO().name("agency name"));

    List<UserSessionResponseDTO> resultList = chatService.getChatsForUserId(USER_ID);

    UserSessionResponseDTO resultUserSessionResponseDTO = resultList.get(0);
    assertNull(resultUserSessionResponseDTO.getSession());
    assertNotNull(resultUserSessionResponseDTO.getChat());
    assertEquals(CHAT_V2.getId(), resultUserSessionResponseDTO.getChat().getId());
    assertEquals(CHAT_V2.getTopic(), resultUserSessionResponseDTO.getChat().getTopic());
    assertThat(
        CHAT_V2.getConsultingTypeId(),
        is(resultUserSessionResponseDTO.getChat().getConsultingType()));
    assertEquals(
        LocalDate.of(
            CHAT_V2.getStartDate().getYear(),
            CHAT_V2.getStartDate().getMonth(),
            CHAT_V2.getStartDate().getDayOfMonth()),
        resultUserSessionResponseDTO.getChat().getStartDate());
    assertEquals(
        LocalTime.of(
            CHAT_V2.getInitialStartDate().getHour(), CHAT_V2.getInitialStartDate().getMinute()),
        resultUserSessionResponseDTO.getChat().getStartTime());
    assertEquals(CHAT_V2.getDuration(), resultUserSessionResponseDTO.getChat().getDuration());
    assertEquals(CHAT_V2.isRepetitive(), resultUserSessionResponseDTO.getChat().isRepetitive());
    assertEquals(CHAT_V2.isActive(), resultUserSessionResponseDTO.getChat().isActive());
    assertEquals(
        CHAT_V2.getMatrixRoomId(), resultUserSessionResponseDTO.getChat().getMatrixRoomId());

    assertNotNull(resultUserSessionResponseDTO.getChat().getModerators());
    assertEquals(1, resultUserSessionResponseDTO.getChat().getModerators().length);
    assertEquals(
        CONSULTANT.getMatrixUserId(), resultUserSessionResponseDTO.getChat().getModerators()[0]);
    assertEquals(1, resultUserSessionResponseDTO.getChat().getAssignedAgencies().size());
    assertEquals(
        "agency name",
        resultUserSessionResponseDTO.getChat().getAssignedAgencies().get(0).getName());
    assertNotNull(resultUserSessionResponseDTO.getChat().getCreatedAt());
    assertEquals(CHAT_HINT_MESSAGE, resultUserSessionResponseDTO.getChat().getHintMessage());
  }

  @Test
  void getChatsForConsultant_Should_ReturnListOfConsultantSessionResponseDTOWithChats() {
    Consultant consultant = Mockito.mock(Consultant.class);

    when(chatRepository.findByAgencyIds(Mockito.any()))
        .thenReturn(singletonList(activeChatWithAgency()));
    when(consultantService.findConsultantsByAgencyIds(Mockito.any()))
        .thenReturn(singletonList(CONSULTANT));

    List<ConsultantSessionResponseDTO> resultList = chatService.getChatsForConsultant(consultant);

    assertNull(resultList.get(0).getSession());
    assertNotNull(resultList.get(0).getChat());
    assertEquals(ACTIVE_CHAT.getId(), resultList.get(0).getChat().getId());
    assertEquals(ACTIVE_CHAT.getTopic(), resultList.get(0).getChat().getTopic());
    assertThat(
        ACTIVE_CHAT.getConsultingTypeId(), is(resultList.get(0).getChat().getConsultingType()));
    assertEquals(
        LocalDate.of(
            ACTIVE_CHAT.getStartDate().getYear(),
            ACTIVE_CHAT.getStartDate().getMonth(),
            ACTIVE_CHAT.getStartDate().getDayOfMonth()),
        resultList.get(0).getChat().getStartDate());
    assertEquals(
        LocalTime.of(
            ACTIVE_CHAT.getInitialStartDate().getHour(),
            ACTIVE_CHAT.getInitialStartDate().getMinute()),
        resultList.get(0).getChat().getStartTime());
    assertEquals(ACTIVE_CHAT.getDuration(), resultList.get(0).getChat().getDuration());
    assertEquals(ACTIVE_CHAT.isRepetitive(), resultList.get(0).getChat().isRepetitive());
    assertEquals(ACTIVE_CHAT.isActive(), resultList.get(0).getChat().isActive());
    assertEquals(ACTIVE_CHAT.getMatrixRoomId(), resultList.get(0).getChat().getMatrixRoomId());
    assertNotNull(resultList.get(0).getChat().getModerators());
    assertEquals(1, resultList.get(0).getChat().getModerators().length);
    assertEquals(CONSULTANT.getMatrixUserId(), resultList.get(0).getChat().getModerators()[0]);
  }

  @Test
  void getChatsForConsultant_Should_ReturnEmptyListWhenListOfChatsIsEmpty() {
    Consultant consultant = Mockito.mock(Consultant.class);

    List<ConsultantSessionResponseDTO> resultList = chatService.getChatsForConsultant(consultant);

    assertThat(resultList, hasSize(0));
  }

  @Test
  void getChatsForConsultant_Should_NotExposeNewSeriesToUninvitedAgencyConsultants() {
    Consultant consultant = Mockito.mock(Consultant.class);
    when(consultant.getId()).thenReturn("uninvited");
    Chat series = activeChatWithAgency();
    when(chatRepository.findByAgencyIds(Mockito.any())).thenReturn(List.of(series));
    when(groupChatParticipantRepository.findBySeriesId(series.getId()))
        .thenReturn(
            List.of(GroupChatParticipant.builder().consultantId("explicit-member").build()));

    List<ConsultantSessionResponseDTO> result = chatService.getChatsForConsultant(consultant);

    assertThat(result, hasSize(0));
  }

  @Test
  void getChat_Should_ReturnChatObject() {
    when(chatRepository.findByIdWithPermissionRelations(CHAT_ID))
        .thenReturn(Optional.of(ACTIVE_CHAT));

    Optional<Chat> result = chatService.getChat(CHAT_ID);

    assertThat(result, instanceOf(Optional.class));
    assertTrue(result.isPresent());
    assertThat(result.get(), instanceOf(Chat.class));
  }

  @Test
  void getChatByGroupId_Should_ReturnChatObject() {
    when(chatRepository.findByMatrixRoomId(MATRIX_ROOM_ID)).thenReturn(Optional.of(ACTIVE_CHAT));

    Optional<Chat> result = chatService.getChatByMatrixRoomId(MATRIX_ROOM_ID);

    assertThat(result, instanceOf(Optional.class));
    assertTrue(result.isPresent());
    assertThat(result.get(), instanceOf(Chat.class));
  }

  @Test
  void updateChat_Should_ThrowBadRequestException_WhenChatDoesNotExist() {
    when(chatRepository.findByIdWithPermissionRelations(CHAT_ID)).thenReturn(Optional.empty());

    try {
      chatService.updateChat(CHAT_ID, CHAT_DTO, AUTHENTICATED_USER);
      fail("Expected exception: BadRequestException");
    } catch (BadRequestException badRequestException) {
      assertTrue(true, "Excepted BadRequestException thrown");
    }
  }

  @Test
  void updateChat_Should_ThrowForbiddenException_WhenCallingConsultantNotOwnerOfChat() {
    when(chatRepository.findByIdWithPermissionRelations(CHAT_ID))
        .thenReturn(Optional.of(INACTIVE_CHAT));

    try {
      chatService.updateChat(CHAT_ID, CHAT_DTO, AUTHENTICATED_USER_3);
      fail("Expected exception: ForbiddenException");
    } catch (ForbiddenException forbiddenException) {
      assertTrue(true, "Excepted ForbiddenException thrown");
    }
  }

  @Test
  void updateChat_Should_ThrowConflictException_WhenChatIsActive() {
    when(chatRepository.findByIdWithPermissionRelations(CHAT_ID))
        .thenReturn(Optional.of(ACTIVE_CHAT));

    try {
      chatService.updateChat(CHAT_ID, CHAT_DTO, AUTHENTICATED_USER_CONSULTANT);
      fail("Expected exception: ConflictException");
    } catch (ConflictException conflictException) {
      assertTrue(true, "Excepted ConflictException thrown");
    }
  }

  @Test
  void updateChat_Should_SaveNewChatSettings() {
    // given
    Chat inactiveChat = new Chat();
    inactiveChat.setActive(false);
    inactiveChat.setChatOwner(CONSULTANT);

    when(chatRepository.findByIdWithPermissionRelations(Mockito.anyLong()))
        .thenReturn(Optional.of(inactiveChat));

    // when
    chatService.updateChat(CHAT_ID, CHAT_DTO, AUTHENTICATED_USER_CONSULTANT);

    // then
    ArgumentCaptor<Chat> chatArgumentCaptor = ArgumentCaptor.forClass(Chat.class);
    verify(chatRepository, times(1)).save(chatArgumentCaptor.capture());
    assertEquals(CHAT_HINT_MESSAGE, chatArgumentCaptor.getValue().getHintMessage());
    verify(participantReconciliationService).reconcile(inactiveChat, CHAT_DTO.getConsultantIds());
  }

  @Test
  void updateChat_Should_PersistIntervalRepeatCountAndModality() {
    // given
    Chat inactiveChat = new Chat();
    inactiveChat.setActive(false);
    inactiveChat.setChatOwner(CONSULTANT);
    when(chatRepository.findByIdWithPermissionRelations(Mockito.anyLong()))
        .thenReturn(Optional.of(inactiveChat));
    ChatDTO scheduleDto =
        ChatDTO.builder()
            .topic(CHAT_TOPIC)
            .startDate(CHAT_START_DATE)
            .startTime(CHAT_START_TIME)
            .duration(CHAT_DURATION)
            .repetitive(true)
            .chatInterval(ChatInterval.MONTHLY)
            .repeatCount(5)
            .modality(ChatModality.VIDEO)
            .build();

    // when
    chatService.updateChat(CHAT_ID, scheduleDto, AUTHENTICATED_USER_CONSULTANT);

    // then
    ArgumentCaptor<Chat> chatArgumentCaptor = ArgumentCaptor.forClass(Chat.class);
    verify(chatRepository, times(1)).save(chatArgumentCaptor.capture());
    Chat saved = chatArgumentCaptor.getValue();
    assertEquals(ChatInterval.MONTHLY, saved.getChatInterval());
    assertEquals(5, saved.getRepeatCount());
    assertEquals(ChatModality.VIDEO, saved.getChatModality());
  }

  @Test
  void updateChat_Should_ApplyCreatePathDefaults_WhenScheduleFieldsAreNull() {
    // given a repetitive update that omits repeatCount / interval / modality
    Chat inactiveChat = new Chat();
    inactiveChat.setActive(false);
    inactiveChat.setChatOwner(CONSULTANT);
    when(chatRepository.findByIdWithPermissionRelations(Mockito.anyLong()))
        .thenReturn(Optional.of(inactiveChat));
    ChatDTO scheduleDto =
        ChatDTO.builder()
            .topic(CHAT_TOPIC)
            .startDate(CHAT_START_DATE)
            .startTime(CHAT_START_TIME)
            .duration(CHAT_DURATION)
            .repetitive(true)
            .build();

    // when
    chatService.updateChat(CHAT_ID, scheduleDto, AUTHENTICATED_USER_CONSULTANT);

    // then the defaults must match the create path (ChatConverter): 12 / WEEKLY / TEXT,
    // NOT drop a repetitive series to a single occurrence.
    ArgumentCaptor<Chat> chatArgumentCaptor = ArgumentCaptor.forClass(Chat.class);
    verify(chatRepository, times(1)).save(chatArgumentCaptor.capture());
    Chat saved = chatArgumentCaptor.getValue();
    assertEquals(12, saved.getRepeatCount());
    assertEquals(ChatInterval.WEEKLY, saved.getChatInterval());
    assertEquals(ChatModality.TEXT, saved.getChatModality());
    assertTrue(saved.isRepetitive());
  }

  @Test
  void updateChat_Should_PersistTimezone_WhenProvided() {
    Chat inactiveChat = new Chat();
    inactiveChat.setActive(false);
    inactiveChat.setChatOwner(CONSULTANT);
    when(chatRepository.findByIdWithPermissionRelations(Mockito.anyLong()))
        .thenReturn(Optional.of(inactiveChat));
    ChatDTO scheduleDto = scheduleDtoBuilder().timezone("Europe/Berlin").build();

    chatService.updateChat(CHAT_ID, scheduleDto, AUTHENTICATED_USER_CONSULTANT);

    ArgumentCaptor<Chat> chatArgumentCaptor = ArgumentCaptor.forClass(Chat.class);
    verify(chatRepository, times(1)).save(chatArgumentCaptor.capture());
    // Recurrence (occurrenceStart) computes DST/monthly/yearly offsets in this zone.
    assertEquals("Europe/Berlin", chatArgumentCaptor.getValue().getTimezone());
  }

  @Test
  void updateChat_Should_PreserveExistingTimezone_WhenOmitted() {
    Chat inactiveChat = new Chat();
    inactiveChat.setActive(false);
    inactiveChat.setChatOwner(CONSULTANT);
    inactiveChat.setTimezone("Europe/Berlin");
    when(chatRepository.findByIdWithPermissionRelations(Mockito.anyLong()))
        .thenReturn(Optional.of(inactiveChat));
    ChatDTO scheduleDto = scheduleDtoBuilder().build(); // no timezone

    chatService.updateChat(CHAT_ID, scheduleDto, AUTHENTICATED_USER_CONSULTANT);

    ArgumentCaptor<Chat> chatArgumentCaptor = ArgumentCaptor.forClass(Chat.class);
    verify(chatRepository, times(1)).save(chatArgumentCaptor.capture());
    assertEquals("Europe/Berlin", chatArgumentCaptor.getValue().getTimezone());
  }

  @Test
  void updateChat_Should_RejectInvalidTimezone() {
    Chat inactiveChat = new Chat();
    inactiveChat.setActive(false);
    inactiveChat.setChatOwner(CONSULTANT);
    when(chatRepository.findByIdWithPermissionRelations(Mockito.anyLong()))
        .thenReturn(Optional.of(inactiveChat));
    ChatDTO scheduleDto = scheduleDtoBuilder().timezone("Not/AZone").build();

    assertThrows(
        BadRequestException.class,
        () -> chatService.updateChat(CHAT_ID, scheduleDto, AUTHENTICATED_USER_CONSULTANT));
    verify(chatRepository, Mockito.never()).save(Mockito.any(Chat.class));
  }

  private static ChatDTO.ChatDTOBuilder scheduleDtoBuilder() {
    return ChatDTO.builder()
        .topic(CHAT_TOPIC)
        .startDate(CHAT_START_DATE)
        .startTime(CHAT_START_TIME)
        .duration(CHAT_DURATION)
        .repetitive(true)
        .chatInterval(ChatInterval.WEEKLY)
        .repeatCount(4);
  }

  @Test
  void updateChat_Should_ResetOccurrenceIndex_WhenScheduleReAnchored() {
    // given a series that has already advanced past its first occurrence
    Chat inactiveChat = new Chat();
    inactiveChat.setActive(false);
    inactiveChat.setChatOwner(CONSULTANT);
    inactiveChat.setCurrentOccurrenceIndex(3);
    when(chatRepository.findByIdWithPermissionRelations(Mockito.anyLong()))
        .thenReturn(Optional.of(inactiveChat));
    ChatDTO scheduleDto =
        ChatDTO.builder()
            .topic(CHAT_TOPIC)
            .startDate(CHAT_START_DATE)
            .startTime(CHAT_START_TIME)
            .duration(CHAT_DURATION)
            .repetitive(true)
            .chatInterval(ChatInterval.WEEKLY)
            .repeatCount(4)
            .build();

    // when the schedule anchor is edited
    chatService.updateChat(CHAT_ID, scheduleDto, AUTHENTICATED_USER_CONSULTANT);

    // then the series restarts from the new anchor, not mid-way through
    ArgumentCaptor<Chat> chatArgumentCaptor = ArgumentCaptor.forClass(Chat.class);
    verify(chatRepository, times(1)).save(chatArgumentCaptor.capture());
    assertEquals(0, chatArgumentCaptor.getValue().getCurrentOccurrenceIndex());
  }

  @Test
  void saveChatAgencyRelation_Should_saveChatAgencyInRepository() {
    ChatAgency chatAgency = new ChatAgency();

    chatService.saveChatAgencyRelation(chatAgency);

    verify(chatAgencyRepository).save(chatAgency);
  }

  @Test
  void saveUserChatRelation_Should_saveUserChatInRepository() {
    UserChat chatUser = new UserChat();

    chatService.saveUserChatRelation(chatUser);

    verify(chatUserRepository).save(chatUser);
  }

  @Test
  void deleteChat_Should_deleteChatInRepository() {
    Chat chat = new Chat();
    chat.setId(CHAT_ID);

    chatService.deleteChat(chat);

    var deletionOrder = inOrder(groupChatParticipantRepository, chatRepository);
    deletionOrder.verify(groupChatParticipantRepository).deleteBySeriesId(CHAT_ID);
    deletionOrder.verify(chatRepository).delete(chat);
    verify(chatRepository).delete(chat);
  }

  @Test
  void saveUserChatRelation_Should_ThrowConflictException_WhenUserAlreadyAssigned() {
    UserChat userChat = new UserChat();
    when(chatUserRepository.findByChatAndUser(Mockito.any(), Mockito.any()))
        .thenReturn(Optional.of(userChat));

    assertThrows(ConflictException.class, () -> chatService.saveUserChatRelation(userChat));
  }

  @Test
  void saveChat_Should_saveChatInRepository() {
    Chat chat = new Chat();
    when(chatRepository.save(chat)).thenReturn(chat);

    Chat result = chatService.saveChat(chat);

    verify(chatRepository).save(chat);
    assertEquals(chat, result);
  }

  @Test
  void saveChatShouldDefaultLegacyGroupChatsToInternalGroup() {
    Chat chat = new Chat();
    when(chatRepository.save(chat)).thenReturn(chat);

    chatService.saveChat(chat);

    assertEquals(
        de.caritas.cob.userservice.api.model.ConversationType.INTERNAL_GROUP,
        chat.getConversationType());
  }

  @Test
  void saveChatShouldPreserveExplicitSelfHelpModality() {
    Chat chat = new Chat();
    chat.setConversationType(de.caritas.cob.userservice.api.model.ConversationType.SELF_HELP);
    when(chatRepository.save(chat)).thenReturn(chat);

    chatService.saveChat(chat);

    assertEquals(
        de.caritas.cob.userservice.api.model.ConversationType.SELF_HELP,
        chat.getConversationType());
  }

  @Test
  void getChatSessionsByIds_Should_returnUserSessionsForGivenIds() {
    when(chatRepository.findAllById(Set.of(CHAT_ID))).thenReturn(List.of(activeChatWithAgency()));

    List<UserSessionResponseDTO> result = chatService.getChatSessionsByIds(Set.of(CHAT_ID));

    assertThat(result, hasSize(1));
    assertNotNull(result.get(0).getChat());
    assertEquals(
        de.caritas.cob.userservice.api.model.ConversationType.SELF_HELP,
        result.get(0).getChat().getConversationType());
  }

  @Test
  void getChatSessionsForConsultantByIds_Should_returnConsultantSessionsForGivenIds() {
    when(chatRepository.findByIdsWithChatAgencies(Set.of(CHAT_ID)))
        .thenReturn(List.of(activeChatWithAgency()));

    List<ConsultantSessionResponseDTO> result =
        chatService.getChatSessionsForConsultantByIds(Set.of(CHAT_ID));

    assertThat(result, hasSize(1));
    assertNotNull(result.get(0).getChat());
  }

  @Test
  void getChatSessionsByGroupIds_Should_returnUserSessionsForGivenGroupIds() {
    when(chatRepository.findByMatrixRoomIdIn(Set.of(MATRIX_ROOM_ID)))
        .thenReturn(List.of(activeChatWithAgency()));

    List<UserSessionResponseDTO> result =
        chatService.getChatSessionsByRoomIds(Set.of(MATRIX_ROOM_ID));

    assertThat(result, hasSize(1));
    assertNotNull(result.get(0).getChat());
  }

  @Test
  void getChatSessionsForConsultantByGroupIds_Should_returnConsultantSessionsForGivenGroupIds() {
    when(chatRepository.findByMatrixRoomIdIn(Set.of(MATRIX_ROOM_ID)))
        .thenReturn(List.of(activeChatWithAgency()));

    List<ConsultantSessionResponseDTO> result =
        chatService.getChatSessionsForConsultantByRoomIds(Set.of(MATRIX_ROOM_ID));

    assertThat(result, hasSize(1));
    assertNotNull(result.get(0).getChat());
  }
}
