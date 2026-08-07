package de.caritas.cob.userservice.api.port.out.identity;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.exception.identity.IdentityProvisioningException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Application-owned result of creating an account in the configured identity provider. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreatedIdentity {

  private String userId;

  /**
   * Returns the usable application identity or fails before callers persist dependent state.
   *
   * @param createdIdentity the configured provider's creation result
   * @return a non-blank identity ID
   */
  public static String requireUserId(CreatedIdentity createdIdentity) {
    if (createdIdentity == null || isBlank(createdIdentity.getUserId())) {
      throw new IdentityProvisioningException("Identity provider returned no user id");
    }
    return createdIdentity.getUserId();
  }
}
