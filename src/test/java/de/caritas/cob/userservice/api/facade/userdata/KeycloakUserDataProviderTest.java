package de.caritas.cob.userservice.api.facade.userdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDataResponseDTO;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.identity.IdentityUserProfile;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KeycloakUserDataProviderTest {

  @Mock AuthenticatedUser authenticatedUser;

  @Mock IdentityClient identityClient;

  @InjectMocks KeycloakUserDataProvider keycloakUserDataProvider;

  @Test
  void retrieveData_Should_ThrowExceptionIfCalledInAnonymousUserContext() {
    // given
    Mockito.when(authenticatedUser.isAnonymous()).thenReturn(true);
    // when, then
    assertThrows(
        IllegalArgumentException.class,
        () -> keycloakUserDataProvider.retrieveAuthenticatedUserData());
  }

  @Test
  void retrieveData_Should_CallKeycloakAndFindExactlyOneUser() {
    // given
    Mockito.when(authenticatedUser.isAnonymous()).thenReturn(false);
    Mockito.when(authenticatedUser.getUserId()).thenReturn("userId");
    IdentityUserProfile identityProfile = givenIdentityProfile();
    Mockito.when(identityClient.getUserProfile("userId")).thenReturn(identityProfile);
    // when
    UserDataResponseDTO userDataResponseDTO =
        keycloakUserDataProvider.retrieveAuthenticatedUserData();
    // then
    assertIdentityProfileAttributesConverted(identityProfile, userDataResponseDTO);
    assertRolesTakenFromAuthenticatedUserBean(userDataResponseDTO);
    assertOtherDtoAttributesSetToDefaults(userDataResponseDTO);
  }

  @Test
  void retrieveData_Should_ReturnTokenBasedUserData_When_KeycloakUserLookupThrows() {
    // given
    Mockito.when(authenticatedUser.isAnonymous()).thenReturn(false);
    Mockito.when(authenticatedUser.getUserId()).thenReturn("userId");
    Mockito.when(authenticatedUser.getUsername()).thenReturn("username");
    Mockito.when(authenticatedUser.getRoles()).thenReturn(Set.of("tenant-admin"));
    Mockito.when(authenticatedUser.getGrantedAuthorities()).thenReturn(Set.of("tenant-admin"));
    Mockito.when(identityClient.getUserProfile("userId"))
        .thenThrow(new RuntimeException("not found"));

    // when
    UserDataResponseDTO userDataResponseDTO =
        keycloakUserDataProvider.retrieveAuthenticatedUserData();

    // then
    assertThat(userDataResponseDTO.getUserId()).isEqualTo("userId");
    assertThat(userDataResponseDTO.getUserName()).isEqualTo("username");
    assertThat(userDataResponseDTO.getUserRoles()).containsExactly("tenant-admin");
    assertThat(userDataResponseDTO.getGrantedAuthorities()).containsExactly("tenant-admin");
    assertOtherDtoAttributesSetToDefaults(userDataResponseDTO);
  }

  @Test
  void retrieveData_Should_ReturnTokenBasedUserData_When_KeycloakUserLookupReturnsNull() {
    // given
    Mockito.when(authenticatedUser.isAnonymous()).thenReturn(false);
    Mockito.when(authenticatedUser.getUserId()).thenReturn("userId");
    Mockito.when(authenticatedUser.getUsername()).thenReturn("username");
    Mockito.when(identityClient.getUserProfile("userId")).thenReturn(null);

    // when
    UserDataResponseDTO userDataResponseDTO =
        keycloakUserDataProvider.retrieveAuthenticatedUserData();

    // then
    assertThat(userDataResponseDTO.getUserId()).isEqualTo("userId");
    assertThat(userDataResponseDTO.getUserName()).isEqualTo("username");
    assertOtherDtoAttributesSetToDefaults(userDataResponseDTO);
  }

  private void assertRolesTakenFromAuthenticatedUserBean(UserDataResponseDTO userDataResponseDTO) {
    assertThat(userDataResponseDTO.getUserRoles()).isEqualTo(authenticatedUser.getRoles());
    assertThat(userDataResponseDTO.getGrantedAuthorities())
        .isEqualTo(authenticatedUser.getGrantedAuthorities());
  }

  private void assertOtherDtoAttributesSetToDefaults(UserDataResponseDTO userDataResponseDTO) {
    assertThat(userDataResponseDTO.getEncourage2fa()).isFalse();
    assertThat(userDataResponseDTO.getAbsenceMessage()).isEmpty();
    assertThat(userDataResponseDTO.isInTeamAgency()).isFalse();
    assertThat(userDataResponseDTO.isHasAnonymousConversations()).isFalse();
    assertThat(userDataResponseDTO.isHasArchive()).isFalse();
    assertThat(userDataResponseDTO.getAgencies()).isEmpty();
  }

  private void assertIdentityProfileAttributesConverted(
      IdentityUserProfile identityProfile, UserDataResponseDTO userDataResponseDTO) {
    assertThat(userDataResponseDTO.getUserId()).isEqualTo(identityProfile.getId());
    assertThat(userDataResponseDTO.getUserName()).isEqualTo(identityProfile.getUsername());
    assertThat(userDataResponseDTO.getEmail()).isEqualTo(identityProfile.getEmail());
    assertThat(userDataResponseDTO.getFirstName()).isEqualTo(identityProfile.getFirstName());
    assertThat(userDataResponseDTO.getLastName()).isEqualTo(identityProfile.getLastName());
  }

  private IdentityUserProfile givenIdentityProfile() {
    return new IdentityUserProfile("id", "username", "firstname", "lastname", "email");
  }
}
