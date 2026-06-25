package de.caritas.cob.userservice.api.service.identity;

import de.caritas.cob.userservice.api.adapters.web.dto.UserIdentitiesDTO;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Resolves which platform identities a single Keycloak user currently holds. Data source for the
 * admin-panel "has rights elsewhere" badge.
 *
 * <p>Part of the multi-identity foundation: the admin and consultant tables are independent (same
 * Keycloak id may hold both an admin and a non-deleted consultant row), so both identities are
 * reported separately alongside the user's current Keycloak realm roles.
 */
@Service
@RequiredArgsConstructor
public class UserIdentitiesService {

  private final @NonNull AdminRepository adminRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull IdentityClient identityClient;

  /**
   * Looks up the identities held by the given user.
   *
   * @param userId the Keycloak id of the user
   * @return a {@link UserIdentitiesDTO} describing the admin/consultant identities and realm roles
   */
  public UserIdentitiesDTO getUserIdentities(String userId) {
    var dto = new UserIdentitiesDTO();
    dto.setHasAdminIdentity(adminRepository.existsById(userId));
    dto.setHasConsultantIdentity(
        consultantRepository.findByIdAndDeleteDateIsNull(userId).isPresent());
    dto.setKeycloakRoles(identityClient.getRealmRoles(userId));
    return dto;
  }
}
