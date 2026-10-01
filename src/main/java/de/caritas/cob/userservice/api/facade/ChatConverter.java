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
      startDate = Chat.toUtc(chatDTO.getStartDate(), chatDTO.getStartTime(), zoneId);
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
            .conversationType(conversationTypeOf(chatDTO))
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

  /**
   * Classifies a create request by group-chat format (ADR-006): anything that repeats is a
   * conversation circle ({@link ConversationType#SELF_HELP}), everything else an internal team chat
   * ({@link ConversationType#INTERNAL_GROUP}). The DTO carries no explicit format field, so this
   * rule is the single source for both the persisted modality and the feature gate (US#1171).
   */
  public static ConversationType conversationTypeOf(ChatDTO chatDTO) {
    return nonNull(chatDTO.getRepeatCount())
            || nonNull(chatDTO.getChatInterval())
            || isTrue(chatDTO.getRepetitive())
        ? ConversationType.SELF_HELP
        : ConversationType.INTERNAL_GROUP;
  }

  /**
   * The persisted format of a group chat. Legacy rows without {@code conversation_type} are
   * classified by the same rule as {@link #conversationTypeOf(ChatDTO)}: anything that repeats is a
   * conversation circle.
   */
  public static ConversationType conversationTypeOf(Chat chat) {
    if (nonNull(chat.getConversationType())) {
      return chat.getConversationType();
    }
    return chat.isRepetitive() || chat.getRepeatCount() > 1 || nonNull(chat.getChatInterval())
        ? ConversationType.SELF_HELP
        : ConversationType.INTERNAL_GROUP;
  }
}
