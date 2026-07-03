package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents a consultant's participation in a group chat (session). Links consultants to group
 * chat sessions (where is_team_session = true).
 */
@Entity
@Table(name = "group_chat_participant")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class GroupChatParticipant {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  /**
   * Identifier of the group chat this participation belongs to. Depending on the caller this holds
   * either a {@code chat.id} (group chats) or a {@code session.id} (team sessions, where {@code
   * is_team_session = true}). The two id spaces are distinct and are NOT interchangeable, so the
   * owning context determines which one is stored. There is no DB-level foreign key, so callers are
   * responsible for resolving it against the correct table.
   */
  @Column(name = "chat_id", nullable = false)
  private Long chatId;

  @Column(name = "consultant_id", nullable = false, length = 36)
  private String consultantId;

  public GroupChatParticipant(Long chatId, String consultantId) {
    this.chatId = chatId;
    this.consultantId = consultantId;
  }

  @Override
  public String toString() {
    return "GroupChatParticipant [id="
        + id
        + ", chatId="
        + chatId
        + ", consultantId="
        + consultantId
        + "]";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof GroupChatParticipant)) {
      return false;
    }
    GroupChatParticipant that = (GroupChatParticipant) o;
    return id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }
}
