package de.caritas.cob.userservice.api.adapters.web.dto;

import static de.caritas.cob.userservice.api.helper.UserHelper.CHAT_MAX_DURATION;
import static de.caritas.cob.userservice.api.helper.UserHelper.CHAT_MIN_DURATION;
import static de.caritas.cob.userservice.api.helper.UserHelper.CHAT_TOPIC_MAX_LENGTH;
import static de.caritas.cob.userservice.api.helper.UserHelper.CHAT_TOPIC_MIN_LENGTH;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.caritas.cob.userservice.api.model.Chat.ChatInterval;
import de.caritas.cob.userservice.api.model.Chat.ChatModality;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.validator.constraints.Length;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;

/** Create new chat model */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ApiModel(value = "Chat")
public class ChatDTO {

  /** Compatibility constructor for existing V1/V2 callers. */
  public ChatDTO(
      String topic,
      LocalDate startDate,
      LocalTime startTime,
      Integer duration,
      Boolean repetitive,
      Long agencyId,
      String hintMessage,
      java.util.List<String> consultantIds) {
    this.topic = topic;
    this.startDate = startDate;
    this.startTime = startTime;
    this.duration = duration;
    this.repetitive = repetitive;
    this.agencyId = agencyId;
    this.hintMessage = hintMessage;
    this.consultantIds = consultantIds;
  }

  @Size(min = CHAT_TOPIC_MIN_LENGTH, max = CHAT_TOPIC_MAX_LENGTH)
  @NotBlank(message = "{chat.name.notBlank}")
  @ApiModelProperty(required = true, example = "Wöchentliche Drogenberatung", position = 0)
  @JsonProperty("topic")
  private String topic;

  @DateTimeFormat(iso = ISO.DATE)
  @ApiModelProperty(required = false, example = "2019-10-23", position = 1)
  @JsonProperty("startDate")
  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
  private LocalDate startDate;

  @DateTimeFormat(pattern = "HH:mm")
  @ApiModelProperty(required = false, example = "12:05", position = 2)
  @JsonProperty("startTime")
  private LocalTime startTime;

  @Min(value = CHAT_MIN_DURATION, message = "{chat.duration.invalid}")
  @Max(value = CHAT_MAX_DURATION, message = "{chat.duration.invalid}")
  @ApiModelProperty(required = false, example = "120", position = 3)
  @JsonProperty("duration")
  private Integer duration;

  @ApiModelProperty(required = false, example = "true", position = 4)
  @JsonProperty("repetitive")
  private Boolean repetitive;

  @Min(value = 1, message = "{chat.repeatCount.invalid}")
  @Max(value = 365, message = "{chat.repeatCount.invalid}")
  @ApiModelProperty(required = false, example = "6", position = 5)
  @JsonProperty("repeatCount")
  private Integer repeatCount;

  @ApiModelProperty(required = false, example = "WEEKLY", position = 6)
  @JsonProperty("chatInterval")
  private ChatInterval chatInterval;

  @ApiModelProperty(required = false, example = "TEXT", position = 7)
  @JsonProperty("modality")
  private ChatModality modality;

  @Size(max = 100)
  @ApiModelProperty(required = false, example = "Europe/Berlin", position = 8)
  @JsonProperty("timezone")
  private String timezone;

  @ApiModelProperty(required = true, example = "5", position = 9)
  @Min(value = 0, message = "{chat.agencyId.invalid}")
  @JsonProperty("agencyId")
  private Long agencyId;

  @ApiModelProperty(required = true, example = "5", position = 10)
  @Length(max = 300, message = "{chat.hintMessage.invalid}")
  @JsonProperty("hintMessage")
  private String hintMessage;

  @Size(max = 10)
  @ApiModelProperty(required = false, example = "de", position = 11)
  @JsonProperty("sourceLanguage")
  private String sourceLanguage;

  @Size(max = 10)
  @ApiModelProperty(required = false, position = 12)
  @JsonProperty("hintMessageTranslations")
  private Map<@Size(max = 10) String, @Size(max = 120) String> hintMessageTranslations;

  @Size(max = 10)
  @ApiModelProperty(required = false, position = 13)
  @JsonProperty("groupChatRulesTranslations")
  private Map<@Size(max = 10) String, @Size(max = 10) List<@NotBlank @Size(max = 120) String>>
      groupChatRulesTranslations;

  @ApiModelProperty(
      required = false,
      example = "[\"consultant-id-1\", \"consultant-id-2\"]",
      position = 14)
  @JsonProperty("consultantIds")
  private java.util.List<String> consultantIds;

  @Override
  public String toString() {
    return "ChatDTO [topic="
        + topic
        + ", agencyId="
        + agencyId
        + ", startDate="
        + startDate
        + ", startTime="
        + startTime
        + ", duration="
        + duration
        + ", repetitive="
        + repetitive
        + ", agencyId="
        + agencyId
        + ", hintMessage="
        + hintMessage
        + ", consultantIds="
        + consultantIds
        + "]";
  }
}
