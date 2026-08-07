package de.caritas.cob.userservice.api.adapters.matrix.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** Synapse admin request used only to reactivate a previously deleted ORISO identity. */
@Getter
@AllArgsConstructor
public class MatrixReactivateUserRequestDTO {
  private boolean deactivated;
}
