package de.caritas.cob.userservice.api.adapters.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import de.caritas.cob.userservice.api.model.Chat.ChatInterval;
import de.caritas.cob.userservice.api.model.Chat.ChatModality;
import de.caritas.cob.userservice.api.model.ConversationType;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/*
 * Represents the chat for the user
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ApiModel(value = "UserChat")
public class UserChatDTO {

  /** Compatibility constructor for consumers of the pre-Series DTO. */
  public UserChatDTO(
      Long id,
      String topic,
      LocalDate startDate,
      LocalTime startTime,
      int duration,
      boolean repetitive,
      boolean active,
      Integer consultingType,
      String lastMessage,
      Long messageDate,
      boolean messagesRead,
      String groupId,
      SessionAttachmentDTO attachment,
      boolean subscribed,
      String[] moderators,
      LocalDateTime startDateWithTime,
      LastMessageDTO e2eLastMessage,
      String createdAt,
      List<AgencyDTO> assignedAgencies,
      String hintMessage) {
    this.id = id;
    this.topic = topic;
    this.startDate = startDate;
    this.startTime = startTime;
    this.duration = duration;
    this.repetitive = repetitive;
    this.active = active;
    this.consultingType = consultingType;
    this.lastMessage = lastMessage;
    this.messageDate = messageDate;
    this.messagesRead = messagesRead;
    this.groupId = groupId;
    this.attachment = attachment;
    this.subscribed = subscribed;
    this.moderators = moderators;
    this.startDateWithTime = startDateWithTime;
    this.e2eLastMessage = e2eLastMessage;
    this.createdAt = createdAt;
    this.assignedAgencies = assignedAgencies;
    this.hintMessage = hintMessage;
  }

  @ApiModelProperty(example = "153918", position = 0)
  private Long id;

  @ApiModelProperty(example = "Drugs", position = 1)
  private String topic;

  @ApiModelProperty(required = true, example = "2019-10-23", position = 2)
  private LocalDate startDate;

  @ApiModelProperty(required = true, example = "12:05", position = 3)
  private LocalTime startTime;

  @ApiModelProperty(required = true, example = "120", position = 4)
  private int duration;

  @ApiModelProperty(required = true, example = "true", position = 5)
  private boolean repetitive;

  @ApiModelProperty(required = true, example = "false", position = 6)
  private boolean active;

  @ApiModelProperty(example = "SELF_HELP")
  private ConversationType conversationType;

  @ApiModelProperty(required = true, example = "0", position = 7)
  private Integer consultingType;

  @ApiModelProperty(example = "Thanks for the answer", position = 8)
  private String lastMessage;

  @ApiModelProperty(example = "1539184948", position = 9)
  private Long messageDate;

  @ApiModelProperty(example = "false", position = 10)
  private boolean messagesRead;

  @ApiModelProperty(example = "xGklslk2JJKK", position = 11)
  private String groupId;

  @ApiModelProperty(position = 12)
  private SessionAttachmentDTO attachment;

  @ApiModelProperty(example = "false", position = 13)
  private boolean subscribed;

  @ApiModelProperty(example = "ajsasdkjsdfkj3, 23njds9f8jhi", position = 14)
  private String[] moderators;

  @JsonIgnore private LocalDateTime startDateWithTime;

  @ApiModelProperty private LastMessageDTO e2eLastMessage;

  @ApiModelProperty private String createdAt;

  @ApiModelProperty private List<AgencyDTO> assignedAgencies;

  @ApiModelProperty private String hintMessage;

  @ApiModelProperty private String sourceLanguage;

  @ApiModelProperty private Map<String, String> hintMessageTranslations;

  @ApiModelProperty private Map<String, List<String>> groupChatRulesTranslations;

  @ApiModelProperty(example = "6")
  private int repeatCount;

  @ApiModelProperty(example = "0")
  private int currentOccurrenceIndex;

  @ApiModelProperty(example = "WEEKLY")
  private ChatInterval chatInterval;

  @ApiModelProperty(example = "TEXT")
  private ChatModality modality;

  @ApiModelProperty(example = "Europe/Berlin")
  private String timezone;

  @ApiModelProperty private List<GroupChatParticipantDTO> participants;
}
