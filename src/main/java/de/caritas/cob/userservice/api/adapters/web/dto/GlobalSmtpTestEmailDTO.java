package de.caritas.cob.userservice.api.adapters.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** The server comes from deployment settings; only the recipient affects sending. */
@Data
public class GlobalSmtpTestEmailDTO {

  @NotBlank @Email private String recipientEmail;

  private String emailThemeColor;
}
