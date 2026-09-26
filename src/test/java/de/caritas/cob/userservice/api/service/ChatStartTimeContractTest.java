package de.caritas.cob.userservice.api.service;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.AUTHENTICATED_USER_CONSULTANT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserChatDTO;
import de.caritas.cob.userservice.api.facade.ChatConverter;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Chat.ChatInterval;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.port.out.ChatAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.chat.GroupChatConsultantAccess;
import de.caritas.cob.userservice.api.service.chat.GroupChatInviteTokenService;
import de.caritas.cob.userservice.api.service.chat.GroupChatParticipantReconciliationService;
import de.caritas.cob.userservice.api.service.notification.GroupAppointmentSeriesEventProducer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

/**
 * Wire contract for group-chat start times (FE#1499): {@code startDate}/{@code startTime} are the
 * wall-clock time in the chat's {@code timezone}, on the way in (create, update) and on the way out
 * (session list). {@code chat.start_date} stays a UTC instant for the scheduler.
 */
@ExtendWith(MockitoExtension.class)
class ChatStartTimeContractTest {

  private static final String BERLIN = "Europe/Berlin";
  private static final long CHAT_ID = 4711L;
  private static final LocalDateTime LONG_AGO = LocalDateTime.of(2020, 1, 1, 0, 0);

  @InjectMocks private ChatService chatService;
  @Mock private ChatRepository chatRepository;
  @Mock private ChatAgencyRepository chatAgencyRepository;
  @Mock private UserChatRepository chatUserRepository;
  @Mock private ConsultantService consultantService;
  @Mock private GroupChatParticipantRepository groupChatParticipantRepository;
  @Mock private GroupChatParticipantReconciliationService participantReconciliationService;
  @Mock private GroupAppointmentSeriesEventProducer appointmentEvents;
  @Mock private GroupChatConsultantAccess groupChatConsultantAccess;
  @Mock private GroupChatInviteTokenService groupChatInviteTokenService;
  @Mock private AgencyService agencyService;

  private final ChatConverter chatConverter = new ChatConverter();

  @BeforeEach
  void noAgencies() {
    lenient().when(chatAgencyRepository.findByChat_IdIn(Mockito.anySet())).thenReturn(List.of());
    lenient().when(chatAgencyRepository.findByChat_Id(Mockito.anyLong())).thenReturn(List.of());
  }

  @Test
  void createThenRead_Should_ReturnTheWallClockTimeTheCounsellorEntered() {
    Chat created = chatConverter.convertToEntity(dto("2026-09-22", "18:54", BERLIN), CONSULTANT);
    created.setId(CHAT_ID);

    // Stored as a UTC instant for the scheduler and reminders.
    assertThat(created.getStartDate()).isEqualTo(LocalDateTime.parse("2026-09-22T16:54"));

    UserChatDTO read = read(created);
    assertThat(read.getStartDate()).isEqualTo(LocalDate.parse("2026-09-22"));
    assertThat(read.getStartTime()).isEqualTo(LocalTime.parse("18:54"));
    assertThat(read.getTimezone()).isEqualTo(BERLIN);
  }

  @Test
  void createThenRead_Should_KeepTheLocalDate_WhenUtcIsStillTheDayBefore() {
    Chat created = chatConverter.convertToEntity(dto("2026-09-25", "01:30", BERLIN), CONSULTANT);
    created.setId(CHAT_ID);

    UserChatDTO read = read(created);
    assertThat(read.getStartDate()).isEqualTo(LocalDate.parse("2026-09-25"));
    assertThat(read.getStartTime()).isEqualTo(LocalTime.parse("01:30"));
  }

  @Test
  void update_Should_StoreTheSameUtcInstantAsCreate() {
    Chat existing = inactiveOwnedChat();
    when(chatRepository.findByIdWithPermissionRelations(CHAT_ID)).thenReturn(Optional.of(existing));

    chatService.updateChat(
        CHAT_ID, dto("2026-09-22", "18:54", BERLIN), AUTHENTICATED_USER_CONSULTANT);

    Chat saved = savedChat();
    assertThat(saved.getStartDate()).isEqualTo(LocalDateTime.parse("2026-09-22T16:54"));
    assertThat(saved.getInitialStartDate()).isEqualTo(LocalDateTime.parse("2026-09-22T16:54"));
    UserChatDTO read = read(saved);
    assertThat(read.getStartTime()).isEqualTo(LocalTime.parse("18:54"));
  }

  @Test
  void update_Should_UseTheStoredTimezone_WhenTheRequestOmitsIt() {
    Chat existing = inactiveOwnedChat();
    existing.setTimezone(BERLIN);
    when(chatRepository.findByIdWithPermissionRelations(CHAT_ID)).thenReturn(Optional.of(existing));

    chatService.updateChat(
        CHAT_ID, dto("2026-12-01", "18:00", null), AUTHENTICATED_USER_CONSULTANT);

    Chat saved = savedChat();
    assertThat(saved.getStartDate()).isEqualTo(LocalDateTime.parse("2026-12-01T17:00"));
    assertThat(read(saved).getStartTime()).isEqualTo(LocalTime.parse("18:00"));
  }

  @Test
  void readUnchangedAndSave_Should_NotMoveTheGroup() {
    Chat created = chatConverter.convertToEntity(dto("2026-09-25", "16:42", BERLIN), CONSULTANT);
    created.setId(CHAT_ID);
    created.setChatOwner(CONSULTANT);
    UserChatDTO shown = read(created);
    when(chatRepository.findByIdWithPermissionRelations(CHAT_ID)).thenReturn(Optional.of(created));

    // The edit form resubmits exactly what the API returned.
    chatService.updateChat(
        CHAT_ID,
        dto(shown.getStartDate().toString(), shown.getStartTime().toString(), shown.getTimezone()),
        AUTHENTICATED_USER_CONSULTANT);

    assertThat(savedChat().getStartDate()).isEqualTo(LocalDateTime.parse("2026-09-25T14:42"));
  }

  @Test
  void weeklySeriesAcrossDstEnd_Should_KeepTheWallClockTimeForEveryOccurrence() {
    ChatDTO weekly = dto("2026-10-20", "18:00", BERLIN);
    weekly.setRepeatCount(3);
    weekly.setChatInterval(ChatInterval.WEEKLY);
    Chat series = chatConverter.convertToEntity(weekly, CONSULTANT);
    series.setId(CHAT_ID);

    assertThat(read(series).getStartTime()).isEqualTo(LocalTime.parse("18:00"));

    // ChatReCreator advances the series the same way after the first occurrence ended.
    series.setStartDate(series.nextStart());
    series.setCurrentOccurrenceIndex(1);

    // 27.10.2026 is after the switch to CET: 17:00 UTC, still 18:00 in Berlin.
    assertThat(series.getStartDate()).isEqualTo(LocalDateTime.parse("2026-10-27T17:00"));
    UserChatDTO read = read(series);
    assertThat(read.getStartDate()).isEqualTo(LocalDate.parse("2026-10-27"));
    assertThat(read.getStartTime()).isEqualTo(LocalTime.parse("18:00"));
  }

  @Test
  void update_Should_StampUpdateDate() {
    Chat existing = inactiveOwnedChat();
    existing.setUpdateDate(LONG_AGO);
    when(chatRepository.findByIdWithPermissionRelations(CHAT_ID)).thenReturn(Optional.of(existing));

    chatService.updateChat(
        CHAT_ID, dto("2026-12-01", "18:00", BERLIN), AUTHENTICATED_USER_CONSULTANT);

    assertThat(savedChat().getUpdateDate()).isAfter(LONG_AGO);
  }

  @Test
  void saveChat_Should_StampUpdateDate_ForStartStopAndLeave() {
    // Start, stop and the last member leaving all persist through saveChat.
    Chat chat = inactiveOwnedChat();
    chat.setUpdateDate(LONG_AGO);
    chat.setActive(true);

    chatService.saveChat(chat);

    assertThat(savedChat().getUpdateDate()).isAfter(LONG_AGO);
  }

  private UserChatDTO read(Chat chat) {
    when(chatRepository.findAllById(Set.of(chat.getId()))).thenReturn(List.of(chat));
    return chatService.getChatSessionsByIds(Set.of(chat.getId())).get(0).getChat();
  }

  private Chat savedChat() {
    ArgumentCaptor<Chat> captor = ArgumentCaptor.forClass(Chat.class);
    verify(chatRepository).save(captor.capture());
    return captor.getValue();
  }

  private static Chat inactiveOwnedChat() {
    Chat chat = new Chat();
    chat.setId(CHAT_ID);
    chat.setActive(false);
    chat.setChatOwner(CONSULTANT);
    chat.setChatAgencies(Set.<ChatAgency>of());
    return chat;
  }

  private static ChatDTO dto(String date, String time, String timezone) {
    return ChatDTO.builder()
        .topic("Gesprächskreis")
        .startDate(LocalDate.parse(date))
        .startTime(LocalTime.parse(time))
        .duration(60)
        .repetitive(false)
        .repeatCount(1)
        .timezone(timezone)
        .build();
  }
}
