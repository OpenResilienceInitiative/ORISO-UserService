package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Chat;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mints a legacy group's invite token exactly once, before returning it to a caller. */
@Service
@RequiredArgsConstructor
public class GroupChatInviteTokenService {

  private final EntityManager entityManager;
  private final NamedParameterJdbcTemplate jdbc;

  private record LockedToken(Long chatId, String token) {}

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

  /** Initializes missing legacy tokens under one ordered row lock for a consultant list. */
  @Transactional
  public Map<Long, String> tokensFor(Collection<Long> chatIds) {
    var ids = chatIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    var rows =
        jdbc.query(
            "SELECT id, invite_token FROM chat WHERE id IN (:ids) ORDER BY id FOR UPDATE",
            Map.of("ids", ids),
            (resultSet, rowNumber) ->
                new LockedToken(resultSet.getLong("id"), resultSet.getString("invite_token")));
    if (rows.size() != ids.size()) {
      throw new NotFoundException("Chat not found");
    }
    var tokens = new HashMap<Long, String>();
    var missing =
        rows.stream()
            .filter(row -> row.token() == null)
            .map(row -> new LockedToken(row.chatId(), GroupChatInviteTokens.newToken()))
            .toList();
    rows.stream()
        .filter(row -> row.token() != null)
        .forEach(row -> tokens.put(row.chatId(), row.token()));
    if (!missing.isEmpty()) {
      jdbc.getJdbcTemplate()
          .batchUpdate(
              "UPDATE chat SET invite_token = ? WHERE id = ? AND invite_token IS NULL",
              new BatchPreparedStatementSetter() {
                @Override
                public void setValues(java.sql.PreparedStatement statement, int index)
                    throws java.sql.SQLException {
                  statement.setString(1, missing.get(index).token());
                  statement.setLong(2, missing.get(index).chatId());
                }

                @Override
                public int getBatchSize() {
                  return missing.size();
                }
              });
      missing.forEach(row -> tokens.put(row.chatId(), row.token()));
    }
    return Map.copyOf(tokens);
  }
}
