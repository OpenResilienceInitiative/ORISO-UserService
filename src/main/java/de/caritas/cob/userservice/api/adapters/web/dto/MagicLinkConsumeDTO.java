package de.caritas.cob.userservice.api.adapters.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MagicLinkConsumeDTO {

  @NotBlank private String token;
}
