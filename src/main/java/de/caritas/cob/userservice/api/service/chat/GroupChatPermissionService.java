package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Central authorization boundary for opening, moderating, and ending a Chat Series occurrence. */
@Service
@RequiredArgsConstructor
public class GroupChatPermissionService {

  private final GroupChatConsultantAccess groupChatConsultantAccess;

  public void requireCanModerate(Chat chat, Consultant consultant) {
    if (!groupChatConsultantAccess.mayStartStopOrBan(chat, consultant)) {
      throw new ForbiddenException(
          "Only a Series Owner or Co-Moderator may moderate this occurrence");
    }
  }
}
