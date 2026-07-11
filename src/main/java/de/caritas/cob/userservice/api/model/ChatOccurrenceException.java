package de.caritas.cob.userservice.api.model;

import de.caritas.cob.userservice.api.model.Chat.ChatModality;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "chat_occurrence_exception",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_chat_occurrence_exception_series_start",
            columnNames = {"series_id", "original_occurrence_start_utc"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatOccurrenceException {

  public enum ExceptionType {
    SKIP,
    OVERRIDE
  }

  @Id
  @SequenceGenerator(
      name = "chat_occurrence_exception_id_seq",
      allocationSize = 1,
      sequenceName = "sequence_chat_occurrence_exception")
  @GeneratedValue(
      strategy = GenerationType.SEQUENCE,
      generator = "chat_occurrence_exception_id_seq")
  private Long id;

  @ManyToOne(optional = false)
  @JoinColumn(name = "series_id", nullable = false)
  private Chat series;

  @Column(name = "original_occurrence_start_utc", nullable = false)
  private LocalDateTime originalOccurrenceStartUtc;

  @Enumerated(EnumType.STRING)
  @Column(name = "exception_type", nullable = false)
  private ExceptionType exceptionType;

  @Column(name = "override_start_utc")
  private LocalDateTime overrideStartUtc;

  @Column(name = "override_duration")
  private Integer overrideDuration;

  @Column(name = "override_capacity")
  private Integer overrideCapacity;

  @Enumerated(EnumType.STRING)
  @Column(name = "override_modality")
  private ChatModality overrideModality;

  public static ChatOccurrenceException skip(Chat series, LocalDateTime originalStartUtc) {
    return ChatOccurrenceException.builder()
        .series(series)
        .originalOccurrenceStartUtc(originalStartUtc)
        .exceptionType(ExceptionType.SKIP)
        .build();
  }
}
