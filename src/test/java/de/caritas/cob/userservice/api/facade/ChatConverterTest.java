package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.CHAT_HINT_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Chat.ChatInterval;
import de.caritas.cob.userservice.api.model.Chat.ChatModality;
import de.caritas.cob.userservice.api.model.Consultant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatConverterTest {

  @Test
  void convertToEntityPreservesMultilingualGroupChatAuthorContent() {
    var request =
        ChatDTO.builder()
            .topic("topic")
            .startDate(LocalDate.of(2022, 8, 12))
            .startTime(LocalTime.of(12, 5))
            .duration(120)
            .repetitive(false)
            .sourceLanguage("de")
            .hintMessageTranslations(Map.of("de", "Willkommen", "en", "Welcome"))
            .groupChatRulesTranslations(
                Map.of(
                    "de", List.of("Respektvoll bleiben"),
                    "en", List.of("Be respectful")))
            .build();

    var chat = new ChatConverter().convertToEntity(request, givenConsultant(), givenAgencyDTO());

    assertThat(chat.getSourceLanguage()).isEqualTo("de");
    assertThat(chat.getHintMessageTranslations()).containsEntry("en", "Welcome");
    assertThat(chat.getGroupChatRulesTranslations())
        .containsEntry("de", List.of("Respektvoll bleiben"));
  }

  @Test
  void convertToEntity_Should_setConsultingTypeId_When_agencyIsNotNull() {
    // given
    ChatDTO chatDTO = givenChatDTO();
    Consultant consultant = givenConsultant();
    AgencyDTO agencyDTO = givenAgencyDTO();

    // when
    ChatConverter chatConverter = new ChatConverter();
    Chat chat = chatConverter.convertToEntity(chatDTO, consultant, agencyDTO);

    // then
    assertThat(chat.getConsultingTypeId()).isEqualTo(1);
  }

  @Test
  void convertToEntity_Should_setConsultingTypeId_When_agencyIsNull() {
    // given
    ChatDTO chatDTO = givenChatDTO();
    Consultant consultant = givenConsultant();

    // when
    ChatConverter chatConverter = new ChatConverter();
    Chat chat = chatConverter.convertToEntity(chatDTO, consultant);

    // then
    assertThat(chat.getConsultingTypeId()).isNull();
  }

  @Test
  void convertToEntity_Should_setTopic() {
    // given
    ChatDTO chatDTO = givenChatDTO();
    Consultant consultant = givenConsultant();
    AgencyDTO agencyDTO = givenAgencyDTO();

    // when
    ChatConverter chatConverter = new ChatConverter();
    Chat chat = chatConverter.convertToEntity(chatDTO, consultant, agencyDTO);

    // then
    assertThat(chat.getTopic()).isEqualTo("topic");
  }

  @Test
  void convertToEntity_Should_setChatOwner() {
    // given
    ChatDTO chatDTO = givenChatDTO();
    Consultant consultant = givenConsultant();
    AgencyDTO agencyDTO = givenAgencyDTO();

    // when
    ChatConverter chatConverter = new ChatConverter();
    Chat chat = chatConverter.convertToEntity(chatDTO, consultant, agencyDTO);

    // then
    assertThat(chat.getChatOwner()).isEqualTo(consultant);
  }

  @Test
  void convertToEntity_Should_setDateTimeAndDuration() {
    // given
    ChatDTO chatDTO = givenChatDTO();
    Consultant consultant = givenConsultant();
    AgencyDTO agencyDTO = givenAgencyDTO();

    // when
    ChatConverter chatConverter = new ChatConverter();
    Chat chat = chatConverter.convertToEntity(chatDTO, consultant, agencyDTO);

    // then
    LocalDateTime startDate = LocalDateTime.of(2022, 8, 12, 12, 5);
    assertThat(chat.getInitialStartDate()).isEqualTo(startDate);
    assertThat(chat.getStartDate()).isEqualTo(startDate);
    assertThat(chat.getDuration()).isEqualTo(120);
  }

  @Test
  void convertToEntity_Should_setIntervalToWeekly_When_repetitiveIsTrue() {
    // given
    ChatDTO chatDTO = givenChatDTO();
    Consultant consultant = givenConsultant();
    AgencyDTO agencyDTO = givenAgencyDTO();

    // when
    ChatConverter chatConverter = new ChatConverter();
    Chat chat = chatConverter.convertToEntity(chatDTO, consultant, agencyDTO);

    // then
    assertThat(chat.isRepetitive()).isTrue();
    assertThat(chat.getChatInterval()).isEqualTo(ChatInterval.WEEKLY);
  }

  @Test
  void convertToEntity_Should_setIntervalToNull_When_repetitiveIsFalse() {
    // given
    ChatDTO chatDTO = givenChatDTO(false);
    Consultant consultant = givenConsultant();
    AgencyDTO agencyDTO = givenAgencyDTO();

    // when
    ChatConverter chatConverter = new ChatConverter();
    Chat chat = chatConverter.convertToEntity(chatDTO, consultant, agencyDTO);

    // then
    assertThat(chat.isRepetitive()).isFalse();
    assertThat(chat.getChatInterval()).isNull();
  }

  @Test
  void convertToEntity_Should_setUpdateDate_And_CreateDate() {
    // given
    ChatDTO chatDTO = givenChatDTO();
    Consultant consultant = givenConsultant();
    AgencyDTO agencyDTO = givenAgencyDTO();

    // when
    ChatConverter chatConverter = new ChatConverter();
    Chat chat = chatConverter.convertToEntity(chatDTO, consultant, agencyDTO);

    // then
    assertThat(chat.getUpdateDate()).isNotNull();
    assertThat(chat.getCreateDate()).isNotNull();
  }

  @Test
  void convertToEntity_Should_setHintMessage() {
    // given
    ChatDTO chatDTO = givenChatDTO();
    Consultant consultant = givenConsultant();
    AgencyDTO agencyDTO = givenAgencyDTO();

    // when
    ChatConverter chatConverter = new ChatConverter();
    Chat chat = chatConverter.convertToEntity(chatDTO, consultant, agencyDTO);

    // then
    assertThat(chat.getHintMessage()).isEqualTo(CHAT_HINT_MESSAGE);
  }

  @Test
  void convertToEntityShouldDefaultAnOldNonRepeatingRequestToOneTextOccurrence() {
    var chat =
        new ChatConverter()
            .convertToEntity(givenChatDTO(false), givenConsultant(), givenAgencyDTO());

    assertThat(chat.getRepeatCount()).isEqualTo(1);
    assertThat(chat.getCurrentOccurrenceIndex()).isZero();
    assertThat(chat.getChatModality()).isEqualTo(ChatModality.TEXT);
    assertThat(chat.getTimezone()).isEqualTo("UTC");
  }

  @Test
  void convertToEntityShouldMapExplicitFiniteSeriesSettings() {
    var request =
        ChatDTO.builder()
            .topic("topic")
            .startDate(LocalDate.of(2022, 8, 12))
            .startTime(LocalTime.of(12, 5))
            .duration(120)
            .repetitive(true)
            .repeatCount(4)
            .chatInterval(ChatInterval.BIWEEKLY)
            .modality(ChatModality.AUDIO)
            .timezone("Europe/Berlin")
            .hintMessage(CHAT_HINT_MESSAGE)
            .build();

    var chat = new ChatConverter().convertToEntity(request, givenConsultant(), givenAgencyDTO());

    assertThat(chat.getRepeatCount()).isEqualTo(4);
    assertThat(chat.getChatInterval()).isEqualTo(ChatInterval.BIWEEKLY);
    assertThat(chat.getChatModality()).isEqualTo(ChatModality.AUDIO);
    assertThat(chat.getTimezone()).isEqualTo("Europe/Berlin");
    assertThat(chat.isRepetitive()).isTrue();
  }

  @Test
  void convertToEntityShouldRejectAnInvalidTimezone() {
    var request =
        ChatDTO.builder()
            .topic("topic")
            .startDate(LocalDate.of(2026, 8, 12))
            .startTime(LocalTime.of(12, 5))
            .timezone("Not/A-Timezone")
            .build();

    assertThatThrownBy(
            () -> new ChatConverter().convertToEntity(request, givenConsultant(), givenAgencyDTO()))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("timezone");
  }

  private ChatDTO givenChatDTO() {
    return givenChatDTO(true);
  }

  private ChatDTO givenChatDTO(boolean repetitive) {
    return ChatDTO.builder()
        .topic("topic")
        .startDate(LocalDate.of(2022, 8, 12))
        .startTime(LocalTime.of(12, 5))
        .duration(120)
        .repetitive(repetitive)
        .hintMessage(CHAT_HINT_MESSAGE)
        .build();
  }

  private Consultant givenConsultant() {
    return Consultant.builder()
        .id("001")
        .rocketChatId("rocketChatId")
        .username("username")
        .firstName("firstName")
        .lastName("lastName")
        .email("email")
        .build();
  }

  private AgencyDTO givenAgencyDTO() {
    return new AgencyDTO().consultingType(1);
  }
}
