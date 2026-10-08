package de.caritas.cob.userservice.api.adapters.matrix.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** Synapse admin request that changes only the inactive flag, without identity erasure. */
@Getter
@AllArgsConstructor
public class MatrixReactivateUserRequestDTO {
  private boolean deactivated;
}
