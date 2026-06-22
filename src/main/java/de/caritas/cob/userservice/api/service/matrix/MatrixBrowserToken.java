package de.caritas.cob.userservice.api.service.matrix;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class MatrixBrowserToken {

  private final String accessToken;
  private final String userId;
  private final String deviceId;
  private final long expiresInMs;
}
