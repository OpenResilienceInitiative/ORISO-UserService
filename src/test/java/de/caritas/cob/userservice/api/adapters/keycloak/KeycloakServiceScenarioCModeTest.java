package de.caritas.cob.userservice.api.adapters.keycloak;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;

/** Actual public adapter boundary: C never silently falls back to stock native administration. */
class KeycloakServiceScenarioCModeTest {
  private final IdentityClientConfig config = mock(IdentityClientConfig.class);
  private final KeycloakClient nativeClient = mock(KeycloakClient.class);
  private final UsersResource users = mock(UsersResource.class);
  private final UserResource user = mock(UserResource.class);
  private final KeycloakService service =
      new KeycloakService(
          new AuthenticatedUser(),
          mock(UserAccountInputValidator.class),
          config,
          nativeClient,
          mock(KeycloakMapper.class),
          mock(UserHelper.class),
          mock(KeycloakAuthClient.class));

  @BeforeEach
  void realNativeFallbackWouldSucceed() {
    when(config.isFiveActorMode()).thenReturn(true);
    when(nativeClient.getUsersResource()).thenReturn(users);
    when(users.get("target")).thenReturn(user);
    var profile = new UserRepresentation();
    profile.setId("target");
    profile.setUsername("actual-target");
    profile.setEmail("target@example.test");
    profile.setEnabled(true);
    profile.setAttributes(Map.of("locale", List.of("de")));
    when(user.toRepresentation()).thenReturn(profile);
  }

  @Test
  void fiveActorsRequiresTheScopedAdapterBeforeRead() {
    assertThrows(IllegalStateException.class, () -> service.findById("target"));
    verifyNoInteractions(nativeClient);
  }

  @Test
  void fiveActorsRequiresTheScopedAdapterBeforePasswordMutation() {
    assertThrows(IllegalStateException.class, () -> service.updatePassword("target", "do-not-log"));
    verify(user, never()).resetPassword(any());
    verifyNoInteractions(nativeClient);
  }

  @Test
  void fiveActorsRequiresTheScopedAdapterBeforeDeactivate() {
    assertThrows(IllegalStateException.class, () -> service.deactivateUser("target"));
    verify(user, never()).update(any());
    verifyNoInteractions(nativeClient);
  }

  @Test
  void fiveActorsRequiresTheScopedAdapterBeforeDeletion() {
    assertThrows(IllegalStateException.class, () -> service.deleteUser("target"));
    verify(user, never()).remove();
    verifyNoInteractions(nativeClient);
  }

  @Test
  void fiveActorsRequiresTheScopedAdapterBeforeEveryOtpRoute() {
    assertAll(
        () ->
            assertThrows(
                IllegalStateException.class, () -> service.getOtpCredential("actual-target")),
        () ->
            assertThrows(
                IllegalStateException.class,
                () -> service.setUpOtpCredential("actual-target", "123456", "do-not-log")),
        () ->
            assertThrows(
                IllegalStateException.class, () -> service.deleteOtpCredential("actual-target")),
        () ->
            assertThrows(
                IllegalStateException.class,
                () -> service.initiateEmailVerification("actual-target", "target@example.test")),
        () ->
            assertThrows(
                IllegalStateException.class,
                () -> service.finishEmailVerification("actual-target", "123456")));
    verifyNoInteractions(nativeClient);
  }

  @Test
  void fiveActorsRollbackPropagatesScopedCommandDemandBeforeNativeCleanup() {
    assertThrows(
        ScopedIdentityCommandRequiredException.class, () -> service.rollbackUser("target"));
    verify(user, never()).remove();
    verifyNoInteractions(nativeClient);
  }

  @Test
  void legacyRollbackRetainsExistingSuccessfulNativeRemoval() {
    when(config.isFiveActorMode()).thenReturn(false);
    assertDoesNotThrow(() -> service.rollbackUser("target"));
    verify(user).remove();
  }

  @Test
  void legacyRollbackRetainsExistingBestEffortNativeFailure() {
    when(config.isFiveActorMode()).thenReturn(false);
    doThrow(new IllegalStateException("Synthetic legacy native failure")).when(user).remove();
    assertDoesNotThrow(() -> service.rollbackUser("target"));
    verify(user).remove();
  }

  @Test
  void legacyModeRetainsExistingProfileProjection() {
    when(config.isFiveActorMode()).thenReturn(false);
    assertEquals("target", service.findById("target").orElseThrow().id());
    assertEquals("actual-target", service.findById("target").orElseThrow().username());
  }
}
