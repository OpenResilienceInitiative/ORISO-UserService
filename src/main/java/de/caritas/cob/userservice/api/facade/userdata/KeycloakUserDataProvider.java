package de.caritas.cob.userservice.api.facade.userdata;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDataResponseDTO;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityProfile;
import de.caritas.cob.userservice.api.port.out.IdentityProfileLookup;
import java.util.Optional;
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
  private final @NonNull IdentityProfileLookup identityProfileLookup;

  public UserDataResponseDTO retrieveAuthenticatedUserData() {
    assertCalledInAuthenticatedUserContext();
    var userId = authenticatedUser.getUserId();
    var profile = Optional.<IdentityProfile>empty();
    try {
      profile = identityProfileLookup.findById(userId);
    } catch (Exception ex) {
      log.warn(
          "Could not retrieve Keycloak user data for authenticated user {}; returning token-based user data; errorType={}",
          userId,
          ex.getClass().getSimpleName());
      return fallbackUserDataResponseDto();
    }
    if (profile.isEmpty()) {
      log.warn(
          "Keycloak user lookup returned no data for authenticated user {}; returning token-based user data",
          userId);
      return fallbackUserDataResponseDto();
    }
    return userDataResponseDtoOf(profile.orElseThrow());
  }

  private void assertCalledInAuthenticatedUserContext() {
    Assert.isTrue(
        !authenticatedUser.isAnonymous(), "Cannot retrieve keycloak data for anonymous users");
  }

  private UserDataResponseDTO userDataResponseDtoOf(IdentityProfile identityProfile) {

    return UserDataResponseDTO.builder()
        .userId(identityProfile.id())
        .userName(identityProfile.username())
        .firstName(identityProfile.firstName())
        .lastName(identityProfile.lastName())
        .email(identityProfile.email())
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
