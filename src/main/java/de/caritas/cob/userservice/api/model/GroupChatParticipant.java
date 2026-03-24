package de.caritas.cob.userservice.api.model;

import java.util.Objects;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
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

  @Column(name = "chat_id", nullable = false)
  private Long chatId; // References chat.id or session.id

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
