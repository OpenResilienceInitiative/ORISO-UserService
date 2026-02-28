package de.caritas.cob.userservice.api.workflow.delete.model;

import java.util.Date;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents information about an inactive Rocket.Chat group, including its last message timestamp.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InactiveGroup {

  private String groupId;
  private Date lastMessageDate;
}
