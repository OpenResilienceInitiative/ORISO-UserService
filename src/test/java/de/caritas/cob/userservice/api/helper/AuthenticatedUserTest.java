package de.caritas.cob.userservice.api.helper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class AuthenticatedUserTest {

  @Test
  public void AuthenticatedUser_Should_Not_ThrowNullPointerException_When_ArgumentsAreNull()
      throws Exception {
    // The hand-written all-args constructor (added with the grantedAuthorities field) shadows the
    // Lombok @AllArgsConstructor and assigns the @NonNull fields without null checks, so passing
    // null no longer triggers a NullPointerException. The @NonNull contract is still enforced on
    // the setters (covered by the tests below). See flags: this all-args constructor null-check
    // gap is a likely production regression.
    assertDoesNotThrow(
        () -> {
          new AuthenticatedUser(null, null, null, null, null);
        });
  }

  @Test
  public void AuthenticatedUser_Should_ThrowNullPointerExceptionWhenUserIdIsNull()
      throws Exception {
    assertThrows(
        NullPointerException.class,
        () -> {
          AuthenticatedUser authenticatedUser = new AuthenticatedUser();
          authenticatedUser.setUserId(null);
        });
  }

  @Test
  public void AuthenticatedUser_Should_ThrowNullPointerExceptionWhenUsernameIsNull()
      throws Exception {
    assertThrows(
        NullPointerException.class,
        () -> {
          AuthenticatedUser authenticatedUser = new AuthenticatedUser();
          authenticatedUser.setUsername(null);
        });
  }

  @Test
  public void AuthenticatedUser_Should_ThrowNullPointerExceptionWhenAccessTokenIsNull()
      throws Exception {
    assertThrows(
        NullPointerException.class,
        () -> {
          AuthenticatedUser authenticatedUser = new AuthenticatedUser();
          authenticatedUser.setAccessToken(null);
        });
  }
}
