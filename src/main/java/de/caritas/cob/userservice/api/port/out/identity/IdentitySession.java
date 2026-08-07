package de.caritas.cob.userservice.api.port.out.identity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Provider-neutral authenticated identity session returned to application services. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class IdentitySession {

  private String accessToken;
  private int expiresIn;
  private int refreshExpiresIn;
  private String refreshToken;
  private String tokenType;
  private String sessionState;
  private String scope;
}
