package de.caritas.cob.userservice.api.adapters.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class GlobalSmtpTestEmailDTO {

  @NotBlank private String host;

  @NotNull
  @Min(1)
  @Max(65535)
  private Integer port;

  @NotNull private Boolean secure;

  @NotBlank @Email private String from;

  @NotBlank @Email private String recipientEmail;

  private String emailThemeColor;
}
