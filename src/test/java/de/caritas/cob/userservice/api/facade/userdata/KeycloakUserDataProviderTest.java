package de.caritas.cob.userservice.api.facade.userdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDataResponseDTO;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityProfile;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
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
    IdentityProfile identityProfile =
        new IdentityProfile("userId", "username", "firstName", "lastName", "email");
    Mockito.when(identityClient.findProfileById("userId")).thenReturn(Optional.of(identityProfile));
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
    Mockito.when(identityClient.findProfileById("userId"))
        .thenThrow(new RuntimeException("not found"));

    UserDataResponseDTO userDataResponseDTO;
    try (var logs = LogbackCaptor.forClass(KeycloakUserDataProvider.class)) {
      userDataResponseDTO = keycloakUserDataProvider.retrieveAuthenticatedUserData();
      assertThat(logs.messages(Level.WARN)).hasSize(1);
      assertThat(logs.messages(Level.WARN).get(0))
          .contains("RuntimeException")
          .doesNotContain("not found");
      assertThat(logs.events())
          .singleElement()
          .satisfies(event -> assertThat(event.getThrowableProxy()).isNull());
    }

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
    Mockito.when(identityClient.findProfileById("userId")).thenReturn(Optional.empty());

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
      IdentityProfile identityProfile, UserDataResponseDTO userDataResponseDTO) {
    assertThat(userDataResponseDTO.getUserId()).isEqualTo(identityProfile.id());
    assertThat(userDataResponseDTO.getUserName()).isEqualTo(identityProfile.username());
    assertThat(userDataResponseDTO.getEmail()).isEqualTo(identityProfile.email());
    assertThat(userDataResponseDTO.getFirstName()).isEqualTo(identityProfile.firstName());
    assertThat(userDataResponseDTO.getLastName()).isEqualTo(identityProfile.lastName());
  }
}
