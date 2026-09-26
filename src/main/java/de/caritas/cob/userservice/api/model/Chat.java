package de.caritas.cob.userservice.api.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.service.chat.GroupChatInviteTokens;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;
import lombok.ToString.Exclude;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "chat")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
public class Chat {

  public enum ChatInterval {
    DAILY,
    WEEKLY,
    BIWEEKLY,
    MONTHLY,
    QUARTERLY,
    YEARLY
  }

  public enum ChatModality {
    TEXT,
    AUDIO,
    VIDEO
  }

  @Id
  @SequenceGenerator(name = "id_seq", allocationSize = 1, sequenceName = "sequence_chat")
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "id_seq")
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  @Column(name = "topic", nullable = false)
  @Size(max = 255)
  @NonNull
  private String topic;

  @Column(name = "consulting_type", updatable = false, columnDefinition = "tinyint(4) unsigned")
  @JdbcTypeCode(SqlTypes.TINYINT)
  private Integer consultingTypeId;

  @Column(name = "initial_start_date", nullable = false)
  @NonNull
  private LocalDateTime initialStartDate;

  @Column(name = "start_date", nullable = false)
  @NonNull
  private LocalDateTime startDate;

  @Column(name = "duration", nullable = false, columnDefinition = "smallint")
  @JdbcTypeCode(SqlTypes.SMALLINT)
  private int duration;

  @Column(name = "is_repetitive", nullable = false)
  private boolean repetitive;

  @Builder.Default
  @Column(name = "repeat_count", nullable = false)
  private int repeatCount = 1;

  @Builder.Default
  @Column(name = "current_occurrence_index", nullable = false)
  private int currentOccurrenceIndex = 0;

  @Builder.Default
  @Column(name = "timezone", nullable = false)
  private String timezone = "UTC";

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(name = "modality", nullable = false)
  private ChatModality chatModality = ChatModality.TEXT;

  @Enumerated(EnumType.STRING)
  @Column(name = "chat_interval")
  private ChatInterval chatInterval;

  @Enumerated(EnumType.STRING)
  @Column(name = "conversation_type", length = 32)
  private ConversationType conversationType;

  @Column(name = "is_active", nullable = false)
  private boolean active;

  @Column(name = "max_participants", columnDefinition = "tinyint(4) unsigned NULL")
  @JdbcTypeCode(SqlTypes.TINYINT)
  private Integer maxParticipants;

  @Column(name = "matrix_room_id")
  private String matrixRoomId;

  @ManyToOne
  @JoinColumn(name = "consultant_id_owner", nullable = false)
  @Fetch(FetchMode.SELECT)
  private Consultant chatOwner;

  @OneToMany(mappedBy = "chat", orphanRemoval = true)
  @Exclude
  private Set<ChatAgency> chatAgencies;

  @OneToMany(mappedBy = "chat", orphanRemoval = true)
  @Exclude
  private Set<UserChat> chatUsers;

  @Column(name = "update_date")
  private LocalDateTime updateDate;

  @Column(name = "create_date")
  private LocalDateTime createDate;

  @Column(name = "hint_message")
  private String hintMessage;

  @Column(name = "source_language", length = 10)
  private String sourceLanguage;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "hint_message_translations", columnDefinition = "json")
  private Map<String, String> hintMessageTranslations;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "group_chat_rules_translations", columnDefinition = "json")
  private Map<String, List<String>> groupChatRulesTranslations;

  /** Secret part of the invite link (#1237); never serialised. */
  @JsonIgnore
  @Exclude
  @Column(name = "invite_token", length = 64)
  private String inviteToken;

  @PrePersist
  void ensureInviteToken() {
    if (inviteToken == null) {
      inviteToken = GroupChatInviteTokens.newToken();
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Chat)) {
      return false;
    }
    var chat = (Chat) o;
    return id.equals(chat.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }

  @JsonIgnore
  public LocalDateTime nextStart() {
    if (repeatCount <= 1 || currentOccurrenceIndex + 1 >= repeatCount) {
      return null;
    }
    return occurrenceStart(currentOccurrenceIndex + 1);
  }

  /** Returns a virtual occurrence calculated from the immutable series anchor. */
  @JsonIgnore
  public LocalDateTime occurrenceStart(int occurrenceIndex) {
    if (occurrenceIndex < 0) {
      throw new IllegalArgumentException("Occurrence index must not be negative");
    }
    if (initialStartDate == null) {
      throw new InternalServerErrorException(
          String.format("Chat with id %s does not have an initial start date.", id));
    }
    if (occurrenceIndex == 0) {
      return initialStartDate;
    }
    if (chatInterval == null) {
      throw new InternalServerErrorException(
          String.format("Chat with id %s does not have a valid interval.", id));
    }
    var localAnchor = initialStartDate.atZone(ZoneOffset.UTC).withZoneSameInstant(zoneId());
    ZonedDateTime occurrence =
        switch (chatInterval) {
          case DAILY -> localAnchor.plusDays(occurrenceIndex);
          case WEEKLY -> localAnchor.plusWeeks(occurrenceIndex);
          case BIWEEKLY -> localAnchor.plusWeeks(2L * occurrenceIndex);
          case MONTHLY -> localAnchor.plusMonths(occurrenceIndex);
          case QUARTERLY -> localAnchor.plusMonths(3L * occurrenceIndex);
          case YEARLY -> localAnchor.plusYears(occurrenceIndex);
        };
    return occurrence.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
  }

  /** The current occurrence start as wall-clock time in the chat's own timezone. */
  @JsonIgnore
  public LocalDateTime localStartDate() {
    return startDate.atZone(ZoneOffset.UTC).withZoneSameInstant(zoneId()).toLocalDateTime();
  }

  /** Wall-clock time in {@code zoneId} to the UTC instant stored in start dates. */
  public static LocalDateTime toUtc(LocalDate date, LocalTime time, ZoneId zoneId) {
    return LocalDateTime.of(date, time)
        .atZone(zoneId)
        .withZoneSameInstant(ZoneOffset.UTC)
        .toLocalDateTime();
  }

  /** The chat's timezone; falls back to UTC for legacy rows without a valid zone. */
  @JsonIgnore
  public ZoneId zoneId() {
    try {
      return ZoneId.of(timezone == null ? "UTC" : timezone);
    } catch (DateTimeException invalidPersistedTimezone) {
      return ZoneOffset.UTC;
    }
  }
}
