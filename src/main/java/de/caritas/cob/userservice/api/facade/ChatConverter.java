package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.BooleanUtils.isTrue;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Chat.ChatInterval;
import de.caritas.cob.userservice.api.model.Consultant;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
public class ChatConverter {

  public Chat convertToEntity(ChatDTO chatDTO, Consultant consultant) {
    return convertToEntity(chatDTO, consultant, null);
  }

  public Chat convertToEntity(ChatDTO chatDTO, Consultant consultant, AgencyDTO agencyDTO) {
    // Handle null dates for group chats (they may not have scheduled start times)
    // Use current time as default for non-scheduled chats
    LocalDateTime startDate = nowInUtc();
    if (nonNull(chatDTO.getStartDate()) && nonNull(chatDTO.getStartTime())) {
      startDate = LocalDateTime.of(chatDTO.getStartDate(), chatDTO.getStartTime());
    }

    Chat.ChatBuilder builder =
        Chat.builder()
            .topic(chatDTO.getTopic())
            .chatOwner(consultant)
            .initialStartDate(startDate)
            .startDate(startDate)
            .duration(chatDTO.getDuration() != null ? chatDTO.getDuration() : 0)
            .repetitive(isTrue(chatDTO.getRepetitive()))
            // Note that the repetition interval can only be weekly atm.
            .chatInterval(isTrue(chatDTO.getRepetitive()) ? ChatInterval.WEEKLY : null)
            .updateDate(nowInUtc())
            .createDate(nowInUtc())
            .hintMessage(chatDTO.getHintMessage());

    if (nonNull(agencyDTO)) {
      builder.consultingTypeId(agencyDTO.getConsultingType());
    }

    return builder.build();
  }
}
