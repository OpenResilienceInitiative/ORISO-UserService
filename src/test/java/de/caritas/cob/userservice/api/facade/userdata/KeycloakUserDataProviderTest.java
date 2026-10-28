package de.caritas.cob.userservice.api.facade.userdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDataResponseDTO;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityProfile;
import de.caritas.cob.userservice.api.port.out.IdentityProfileLookup;
import java.util.Optional;
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

  @Mock IdentityProfileLookup identityProfileLookup;

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
    IdentityProfile profile = givenIdentityProfile();
    Mockito.when(identityProfileLookup.findById("userId")).thenReturn(Optional.of(profile));
    // when
    UserDataResponseDTO userDataResponseDTO =
        keycloakUserDataProvider.retrieveAuthenticatedUserData();
    // then
    assertIdentityProfileAttributesConverted(profile, userDataResponseDTO);
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
    Mockito.when(identityProfileLookup.findById("userId"))
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
  void retrieveData_Should_ReturnTokenBasedUserData_When_IdentityProfileIsAbsent() {
    // given
    Mockito.when(authenticatedUser.isAnonymous()).thenReturn(false);
    Mockito.when(authenticatedUser.getUserId()).thenReturn("userId");
    Mockito.when(authenticatedUser.getUsername()).thenReturn("username");
    Mockito.when(identityProfileLookup.findById("userId")).thenReturn(Optional.empty());

    // when
    UserDataResponseDTO userDataResponseDTO =
        keycloakUserDataProvider.retrieveAuthenticatedUserData();

    // then
    assertThat(userDataResponseDTO.getUserId()).isEqualTo("userId");
    assertThat(userDataResponseDTO.getUserName()).isEqualTo("username");
    assertOtherDtoAttributesSetToDefaults(userDataResponseDTO);
  }

  @Test
  void scopedCurrentUserNeverFallsBackToLegacyProfileOrTokenWhenOwnerMissing() {
    var mode = Mockito.mock(de.caritas.cob.userservice.api.port.out.IdentityClientConfig.class);
    Mockito.when(mode.isFiveActorMode()).thenReturn(true);
    org.springframework.test.util.ReflectionTestUtils.setField(
        keycloakUserDataProvider, "identityConfig", mode);
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException.class,
        () -> keycloakUserDataProvider.retrieveAuthenticatedUserData());
    Mockito.verifyNoInteractions(identityProfileLookup);
  }

  @Test
  void scopedCurrentProfileUsesOnlyTheInstalledSelfReader() {
    var mode = Mockito.mock(de.caritas.cob.userservice.api.port.out.IdentityClientConfig.class);
    var owner =
        Mockito.mock(
            de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfAppOtpMaintenance.class);
    Mockito.when(mode.isFiveActorMode()).thenReturn(true);
    Mockito.when(owner.isFiveActorMode()).thenReturn(true);
    Mockito.when(owner.readCurrentProfile()).thenReturn(givenIdentityProfile());
    keycloakUserDataProvider.configureIdentityModel(mode);
    keycloakUserDataProvider.configureCurrentSelf(owner);
    assertIdentityProfileAttributesConverted(
        givenIdentityProfile(), keycloakUserDataProvider.retrieveAuthenticatedUserData());
    Mockito.verify(owner).readCurrentProfile();
    Mockito.verifyNoInteractions(identityProfileLookup);
  }

  @Test
  void scopedNativeReadFailureCannotReturnTokenMetadata() {
    var mode = Mockito.mock(de.caritas.cob.userservice.api.port.out.IdentityClientConfig.class);
    var owner =
        Mockito.mock(
            de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfAppOtpMaintenance.class);
    Mockito.when(mode.isFiveActorMode()).thenReturn(true);
    Mockito.when(owner.isFiveActorMode()).thenReturn(true);
    Mockito.when(owner.readCurrentProfile())
        .thenThrow(
            new de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException(
                "unknown"));
    keycloakUserDataProvider.configureIdentityModel(mode);
    keycloakUserDataProvider.configureCurrentSelf(owner);
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException.class,
        () -> keycloakUserDataProvider.retrieveAuthenticatedUserData());
    Mockito.verifyNoInteractions(identityProfileLookup);
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
      IdentityProfile profile, UserDataResponseDTO userDataResponseDTO) {
    assertThat(userDataResponseDTO.getUserId()).isEqualTo(profile.id());
    assertThat(userDataResponseDTO.getUserName()).isEqualTo(profile.username());
    assertThat(userDataResponseDTO.getEmail()).isEqualTo(profile.email());
    assertThat(userDataResponseDTO.getFirstName()).isEqualTo(profile.firstName());
    assertThat(userDataResponseDTO.getLastName()).isEqualTo(profile.lastName());
  }

  private IdentityProfile givenIdentityProfile() {
    return new IdentityProfile("id", "username", "firstname", "lastname", "email");
  }
}
