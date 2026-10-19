package de.caritas.cob.userservice.api.service.agency.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
// UserService rolls out before AgencyService; discard the old password field, never retain it.
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgencyMatrixCredentialsDTO {

  private String matrixUserId;
}
