package de.caritas.cob.userservice.api.workflow.delete.model;

import java.time.LocalDateTime;
import java.util.Date;
import lombok.Builder;
import lombok.Data;

/**
 * Represents information about a successfully deleted user/session during the deletion workflow.
 */
@Data
@Builder
public class DeletionWorkflowInfo {

  private String userId;
  private String userName;
  private String groupId;
  private Date lastMessageDate;
  private LocalDateTime deletionTimestamp;
}

