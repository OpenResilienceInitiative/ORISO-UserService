package de.caritas.cob.userservice.api.adapters.matrix.dto;

import lombok.Getter;
import lombok.Setter;

/** DTO for Matrix user creation request. */
@Getter
@Setter
public class MatrixCreateUserRequestDTO {

  private String username;
  private String password;
  private String displayName;
  private boolean admin;
  private String nonce;
  private String mac;
}
