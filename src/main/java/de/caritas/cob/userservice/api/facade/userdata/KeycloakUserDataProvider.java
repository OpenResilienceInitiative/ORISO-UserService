package de.caritas.cob.userservice.api.facade.userdata;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDataResponseDTO;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.identity.IdentityUserProfile;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/** Provider for consultant information. */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeycloakUserDataProvider {

  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull IdentityClient identityClient;

  public UserDataResponseDTO retrieveAuthenticatedUserData() {
    assertCalledInAuthenticatedUserContext();
    IdentityUserProfile user;
    try {
      user = identityClient.getUserProfile(authenticatedUser.getUserId());
    } catch (Exception ex) {
      log.warn(
          "Could not retrieve Keycloak user data for authenticated user {}; returning token-based user data",
          authenticatedUser.getUserId(),
          ex);
      return fallbackUserDataResponseDto();
    }
    if (user == null) {
      log.warn(
          "Keycloak user lookup returned no data for authenticated user {}; returning token-based user data",
          authenticatedUser.getUserId());
      return fallbackUserDataResponseDto();
    }
    return userDataResponseDtoOf(user);
  }

  private void assertCalledInAuthenticatedUserContext() {
    Assert.isTrue(
        !authenticatedUser.isAnonymous(), "Cannot retrieve keycloak data for anonymous users");
  }

  private UserDataResponseDTO userDataResponseDtoOf(IdentityUserProfile identityProfile) {

    return UserDataResponseDTO.builder()
        .userId(identityProfile.getId())
        .userName(identityProfile.getUsername())
        .firstName(identityProfile.getFirstName())
        .lastName(identityProfile.getLastName())
        .email(identityProfile.getEmail())
        .encourage2fa(false)
        .absenceMessage("")
        .isInTeamAgency(false)
        .agencies(Lists.newArrayList())
        .userRoles(authenticatedUser.getRoles())
        .grantedAuthorities(authenticatedUser.getGrantedAuthorities())
        .hasAnonymousConversations(false)
        .hasArchive(false)
        .build();
  }

  private UserDataResponseDTO fallbackUserDataResponseDto() {
    return UserDataResponseDTO.builder()
        .userId(authenticatedUser.getUserId())
        .userName(authenticatedUser.getUsername())
        .encourage2fa(false)
        .absenceMessage("")
        .isInTeamAgency(false)
        .agencies(Lists.newArrayList())
        .userRoles(authenticatedUser.getRoles())
        .grantedAuthorities(authenticatedUser.getGrantedAuthorities())
        .hasAnonymousConversations(false)
        .hasArchive(false)
        .build();
  }
}
