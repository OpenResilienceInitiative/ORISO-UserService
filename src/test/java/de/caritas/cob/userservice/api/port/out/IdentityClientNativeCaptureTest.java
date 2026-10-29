package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import de.caritas.cob.userservice.api.port.out.identity.NativeCreationCapture;
import org.junit.jupiter.api.Test;

class IdentityClientNativeCaptureTest {

  @Test
  void unsupportedTrackedClientNeverInvokesLegacyCreate() {
    var legacy = mock(IdentityClient.class);
    var client = new LegacyOnlyClient(legacy);
    var capture = mock(NativeCreationCapture.class);

    assertThatThrownBy(() -> client.createUser(new UserDTO(), null, null, capture))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("Native creation capture is not supported");

    verifyNoInteractions(legacy, capture);
  }

  /** Implements only the original port: the default tracked method must fail before delegation. */
  private record LegacyOnlyClient(IdentityClient delegate) implements IdentityClient {
    @Override
    public boolean changePassword(String userId, String password) {
      return delegate.changePassword(userId, password);
    }

    @Override
    public void changeLanguage(String userId, String language) {
      delegate.changeLanguage(userId, language);
    }

    @Override
    public CreatedIdentity createUser(UserDTO user) {
      return delegate.createUser(user);
    }

    @Override
    public CreatedIdentity createUser(UserDTO user, String firstName, String lastName) {
      return delegate.createUser(user, firstName, lastName);
    }

    @Override
    public void updateUserRole(String userId) {
      delegate.updateUserRole(userId);
    }

    @Override
    public void updateRole(String userId, UserRole role) {
      delegate.updateRole(userId, role);
    }

    @Override
    public void removeRoleIfPresent(String userId, String roleName) {
      delegate.removeRoleIfPresent(userId, roleName);
    }

    @Override
    public void updateRole(String userId, String roleName) {
      delegate.updateRole(userId, roleName);
    }
  }
}
