package de.caritas.cob.userservice.api.adapters.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AskerReactivationRequestDTO {

  @NotBlank
  @Size(max = 255)
  private String username;

  @NotBlank
  @Email
  @Size(max = 255)
  private String email;

  @NotNull @PositiveOrZero private Long tenantId;

  @NotBlank private String password;
}
