package de.caritas.cob.userservice.api.adapters.web.dto;

import javax.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MagicLinkRequestDTO {

  @NotBlank private String username;
}

