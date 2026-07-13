package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.BooleanUtils.isTrue;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Chat.ChatInterval;
import de.caritas.cob.userservice.api.model.Chat.ChatModality;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

@Component
public class ChatConverter {

  public Chat convertToEntity(ChatDTO chatDTO, Consultant consultant) {
    return convertToEntity(chatDTO, consultant, null);
  }

  public Chat convertToEntity(ChatDTO chatDTO, Consultant consultant, AgencyDTO agencyDTO) {
    // Handle null dates for group chats (they may not have scheduled start times)
    // Use current time as default for non-scheduled chats
    String timezone =
        nonNull(chatDTO.getTimezone()) && !chatDTO.getTimezone().isBlank()
            ? chatDTO.getTimezone()
            : "UTC";
    ZoneId zoneId;
    try {
      zoneId = ZoneId.of(timezone);
    } catch (DateTimeException invalidTimezone) {
      throw new BadRequestException("Invalid timezone: " + timezone, invalidTimezone);
    }
    LocalDateTime startDate = nowInUtc();
    if (nonNull(chatDTO.getStartDate()) && nonNull(chatDTO.getStartTime())) {
      startDate =
          LocalDateTime.of(chatDTO.getStartDate(), chatDTO.getStartTime())
              .atZone(zoneId)
              .withZoneSameInstant(ZoneOffset.UTC)
              .toLocalDateTime();
    }

    int repeatCount =
        nonNull(chatDTO.getRepeatCount())
            ? chatDTO.getRepeatCount()
            : (isTrue(chatDTO.getRepetitive()) ? 12 : 1);
    ChatInterval interval =
        repeatCount > 1
            ? (nonNull(chatDTO.getChatInterval()) ? chatDTO.getChatInterval() : ChatInterval.WEEKLY)
            : null;

    Chat.ChatBuilder builder =
        Chat.builder()
            .topic(chatDTO.getTopic())
            .chatOwner(consultant)
            .initialStartDate(startDate)
            .startDate(startDate)
            .duration(chatDTO.getDuration() != null ? chatDTO.getDuration() : 0)
            .repetitive(repeatCount > 1)
            .repeatCount(repeatCount)
            .currentOccurrenceIndex(0)
            .chatInterval(interval)
            .timezone(timezone)
            .chatModality(
                nonNull(chatDTO.getModality()) ? chatDTO.getModality() : ChatModality.TEXT)
            .conversationType(
                nonNull(chatDTO.getRepeatCount())
                        || nonNull(chatDTO.getChatInterval())
                        || isTrue(chatDTO.getRepetitive())
                    ? ConversationType.SELF_HELP
                    : ConversationType.INTERNAL_GROUP)
            .updateDate(nowInUtc())
            .createDate(nowInUtc())
            .hintMessage(chatDTO.getHintMessage())
            .sourceLanguage(chatDTO.getSourceLanguage())
            .hintMessageTranslations(chatDTO.getHintMessageTranslations())
            .groupChatRulesTranslations(chatDTO.getGroupChatRulesTranslations());

    if (nonNull(agencyDTO)) {
      builder.consultingTypeId(agencyDTO.getConsultingType());
    }

    return builder.build();
  }
}
