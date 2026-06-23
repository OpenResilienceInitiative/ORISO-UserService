package de.caritas.cob.userservice.api.adapters.web.dto;

import java.util.List;
import lombok.Data;

/**
 * Data source for the admin-panel "has rights elsewhere" badge.
 *
 * <p>Describes which platform identities a single Keycloak user currently holds: whether the user
 * has an admin row, whether the user has a (non-deleted) consultant row and the realm roles
 * currently assigned in Keycloak. Part of the multi-identity foundation where the same Keycloak id
 * may legitimately hold both an admin and a consultant identity.
 */
@Data
public class UserIdentitiesDTO {

  /** Whether an {@code admin} row exists for the user id. */
  private boolean hasAdminIdentity;

  /** Whether a non-deleted {@code consultant} row exists for the user id. */
  private boolean hasConsultantIdentity;

  /** The realm role names currently assigned to the user in Keycloak. */
  private List<String> keycloakRoles;
}
