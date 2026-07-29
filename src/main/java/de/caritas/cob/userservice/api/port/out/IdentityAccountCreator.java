package de.caritas.cob.userservice.api.port.out;

/** Focused outbound contract for identity account creation. */
public interface IdentityAccountCreator {

  IdentityAccountCreated createAccount(IdentityAccountCreation account);
}
