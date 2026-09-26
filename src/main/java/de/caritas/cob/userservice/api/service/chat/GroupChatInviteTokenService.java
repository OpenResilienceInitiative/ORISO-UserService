package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Chat;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mints a legacy group's invite token exactly once, before returning it to a caller. */
@Service
@RequiredArgsConstructor
public class GroupChatInviteTokenService {

  private final EntityManager entityManager;

  @Transactional
  public String tokenFor(Long chatId) {
    var chat = entityManager.find(Chat.class, chatId, LockModeType.PESSIMISTIC_WRITE);
    if (chat == null) {
      throw new NotFoundException("Chat not found");
    }
    // A caller may already have loaded the row before another transaction minted its token.
    entityManager.refresh(chat, LockModeType.PESSIMISTIC_WRITE);
    if (chat.getInviteToken() == null) {
      chat.setInviteToken(GroupChatInviteTokens.newToken());
      entityManager.flush();
    }
    return chat.getInviteToken();
  }
}
