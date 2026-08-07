package de.caritas.cob.userservice.api.exception.identity;

/** Signals that an identity provider did not create a usable application identity. */
public class IdentityProvisioningException extends RuntimeException {

  private static final long serialVersionUID = 2553770152985680364L;

  public IdentityProvisioningException(String message) {
    super(message);
  }
}
