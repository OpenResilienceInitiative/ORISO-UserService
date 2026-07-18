package de.caritas.cob.userservice.api.adapters.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PasswordResetRequestDTO {

  @NotBlank private String username;
  private String locale;
  private PasswordResetApplication application = PasswordResetApplication.APP;
}
