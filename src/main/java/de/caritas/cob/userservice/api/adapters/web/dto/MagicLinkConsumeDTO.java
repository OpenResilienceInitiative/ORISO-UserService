package de.caritas.cob.userservice.api.adapters.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class MagicLinkConsumeDTO {

  @NotBlank private String token;

  @Pattern(regexp = "[0-9]{6}")
  private String otp;
}
