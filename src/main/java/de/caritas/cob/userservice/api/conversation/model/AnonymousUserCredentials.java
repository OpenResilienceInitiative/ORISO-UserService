package de.caritas.cob.userservice.api.conversation.model;

import lombok.Builder;
import lombok.Data;

/** Identity credentials for an anonymous Matrix user. */
@Data
@Builder
public class AnonymousUserCredentials {

  private String userId;
  private String accessToken;
  private int expiresIn;
  private String refreshToken;
  private int refreshExpiresIn;
}
