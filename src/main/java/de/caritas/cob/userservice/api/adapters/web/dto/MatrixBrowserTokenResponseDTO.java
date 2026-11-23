package de.caritas.cob.userservice.api.adapters.web.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** Browser login response without a secret-bearing toString for Spring MVC body logging. */
@Getter
@AllArgsConstructor
public class MatrixBrowserTokenResponseDTO {
  private final String accessToken;
  private final String userId;
  private final String deviceId;
  private final String uiaPassword;
  private final long expiresInMs;
}
