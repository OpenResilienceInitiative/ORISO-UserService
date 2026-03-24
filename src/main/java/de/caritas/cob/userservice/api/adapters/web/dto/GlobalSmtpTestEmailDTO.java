package de.caritas.cob.userservice.api.adapters.web.dto;

import javax.validation.constraints.Email;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import lombok.Data;

@Data
public class GlobalSmtpTestEmailDTO {

  @NotBlank private String host;

  @NotNull
  @Min(1)
  @Max(65535)
  private Integer port;

  @NotNull private Boolean secure;

  @NotBlank private String username;

  @NotBlank private String password;

  @NotBlank
  @Email
  private String from;

  @NotBlank
  @Email
  private String recipientEmail;

  private String emailThemeColor;
}


