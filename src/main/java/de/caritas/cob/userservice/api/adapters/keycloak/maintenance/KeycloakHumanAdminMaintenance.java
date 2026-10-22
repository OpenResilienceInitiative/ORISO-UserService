package de.caritas.cob.userservice.api.adapters.keycloak.maintenance;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.port.out.HumanAdminMaintenanceTransport;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdate;
import de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance.CheckedAccount;
import java.util.UUID;
import org.springframework.web.client.RestTemplate;

/** Closed first counterfactual for the fixed checked read/profile native transport. */
public final class KeycloakHumanAdminMaintenance implements HumanAdminMaintenanceTransport {
  public KeycloakHumanAdminMaintenance(
      RestTemplate http,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      AccountLifecycleCodec codec,
      String authServer,
      String realmName,
      String realmId) {}

  @Override
  public Projection read(CheckedAccount account) {
    throw new IllegalStateException("Checked admin maintenance is unavailable");
  }

  @Override
  public String profileRequestDigest(
      CheckedAccount account, IdentityProfileUpdate profile, UUID operation) {
    throw new IllegalStateException("Checked admin maintenance is unavailable");
  }

  @Override
  public FrozenProfile freezeProfile(
      CheckedAccount account, IdentityProfileUpdate profile, UUID operation, String expectedState) {
    throw new IllegalStateException("Checked admin maintenance is unavailable");
  }

  @Override
  public String updateProfile(CheckedAccount account, FrozenProfile frozen) {
    throw new IllegalStateException("Checked admin maintenance is unavailable");
  }
}
