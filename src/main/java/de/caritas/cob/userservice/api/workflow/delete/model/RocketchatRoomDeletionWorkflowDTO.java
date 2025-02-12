package de.caritas.cob.userservice.api.workflow.delete.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RocketchatRoomDeletionWorkflowDTO {

  private String rocketchatRoomId;
  private List<DeletionWorkflowError> deletionWorkflowErrors;
}
