package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance.CheckedAccount;
import java.util.List;
import java.util.UUID;

/** Fixed native operations consume only the private current application authorization. */
public interface HumanAdminMaintenanceTransport {
  record Projection(
      String id,
      String username,
      String email,
      String firstName,
      String lastName,
      boolean enabled,
      boolean emailVerified,
      Long tenantId,
      String preferredLanguage,
      List<String> effectiveRoles,
      boolean passwordChangeRequired,
      String state) {
    public Projection {
      effectiveRoles = List.copyOf(effectiveRoles);
    }
  }

  record FrozenProfile(String body, String bindingDigest) {
    @Override
    public String toString() {
      return "FrozenProfile[payload=redacted]";
    }
  }

  Projection read(CheckedAccount account);

  String profileRequestDigest(
      CheckedAccount account, IdentityProfileUpdate profile, UUID operation);

  FrozenProfile freezeProfile(
      CheckedAccount account, IdentityProfileUpdate profile, UUID operation, String expectedState);

  String updateProfile(CheckedAccount account, FrozenProfile frozen);

  String verifyProfileReceipt(CheckedAccount account, FrozenProfile frozen, String receipt);
}
