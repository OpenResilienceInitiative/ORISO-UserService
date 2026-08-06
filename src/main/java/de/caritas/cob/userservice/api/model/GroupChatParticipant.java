package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

  public enum ParticipantRole {
    OWNER,
    CO_MODERATOR,
    PARTICIPANT
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  /**
   * References {@code session.id} of the team session this participation belongs to (where {@code
   * is_team_session = true}). The column is named {@code chat_id} for historical reasons; despite
   * the name it does <b>not</b> reference {@code chat.id}. All current write paths (via {@link
   * de.caritas.cob.userservice.api.facade.CreateChatFacade}) store the session ID, and readers
   * (e.g. {@link de.caritas.cob.userservice.api.service.session.SessionService}) pass this value
   * directly to {@code sessionRepository.findAllById}. There is no DB-level foreign key.
   */
  @Column(name = "chat_id", nullable = false)
  private Long chatId;

  @Column(name = "consultant_id", nullable = false, length = 36)
  private String consultantId;

  /** Canonical Series relation for new self-help-group data. */
  @Column(name = "series_id")
  private Long seriesId;

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(name = "participant_role", nullable = false)
  private ParticipantRole role = ParticipantRole.PARTICIPANT;

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
        + ", seriesId="
        + seriesId
        + ", role="
        + role
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
