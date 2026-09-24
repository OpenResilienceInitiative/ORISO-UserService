package de.caritas.cob.userservice.api.adapters.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** The server always comes from the stored platform settings; the caller only picks the inbox. */
@Data
public class GlobalSmtpTestEmailDTO {

  @NotBlank @Email private String recipientEmail;

  private String emailThemeColor;
}
