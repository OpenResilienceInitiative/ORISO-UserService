package de.caritas.cob.userservice.api.adapters.web.dto;

import de.caritas.cob.userservice.api.helper.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PasswordResetConfirmDTO {

  @NotBlank private String token;

  @NotBlank
  @Size(min = PasswordPolicy.MIN_LENGTH)
  private String newPassword;
}
