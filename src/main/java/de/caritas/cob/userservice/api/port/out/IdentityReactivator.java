package de.caritas.cob.userservice.api.port.out;

/** Privileged identity reactivation after exact application-account validation. */
public interface IdentityReactivator {

  void reactivateUser(
      String userId,
      String expectedUsername,
      String expectedEmail,
      Long expectedTenantId,
      String password);
}
