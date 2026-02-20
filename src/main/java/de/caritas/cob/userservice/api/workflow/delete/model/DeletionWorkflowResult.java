package de.caritas.cob.userservice.api.workflow.delete.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import static java.util.Objects.nonNull;

/**
 * Represents the result of a deletion workflow operation, containing both errors and successful
 * deletion information.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeletionWorkflowResult {

  @Builder.Default private List<DeletionWorkflowError> errors = new ArrayList<>();

  @Builder.Default private List<DeletionWorkflowInfo> deletionInfo = new ArrayList<>();

  public void addInfo(DeletionWorkflowInfo info) {
    deletionInfo.add(info);
  }

  public void addAllErrors(Collection<? extends DeletionWorkflowError> errorList) {
    errors.addAll(errorList);
  }

  public void merge(DeletionWorkflowResult other) {
    if (nonNull(other)) {
      errors.addAll(other.getErrors());
      deletionInfo.addAll(other.getDeletionInfo());
    }
  }

}

