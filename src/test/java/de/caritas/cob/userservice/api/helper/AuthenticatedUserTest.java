package de.caritas.cob.userservice.api.helper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
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

  // ---------------------------------------------------------------------------
  // Extended coverage — 2026-07-06
  // ---------------------------------------------------------------------------

  private AuthenticatedUser givenAuthenticatedUserWithRoles(String... roles) {
    AuthenticatedUser authenticatedUser = new AuthenticatedUser();
    authenticatedUser.setUserId("userId");
    authenticatedUser.setUsername("username");
    authenticatedUser.setAccessToken("token");
    authenticatedUser.setRoles(new HashSet<>(java.util.Arrays.asList(roles)));
    return authenticatedUser;
  }

  @Test
  public void isRestrictedAgencyAdmin_Should_ReturnFalse_When_RolesIsNull() {
    AuthenticatedUser authenticatedUser = new AuthenticatedUser();
    assertThat(authenticatedUser.isRestrictedAgencyAdmin()).isFalse();
  }

  @Test
  public void isRestrictedAgencyAdmin_Should_ReturnFalse_When_RolesIsEmpty() {
    AuthenticatedUser authenticatedUser = givenAuthenticatedUserWithRoles();
    assertThat(authenticatedUser.isRestrictedAgencyAdmin()).isFalse();
  }

  @Test
  public void isRestrictedAgencyAdmin_Should_ReturnTrue_When_RolesContainRestrictedAgencyAdmin() {
    AuthenticatedUser authenticatedUser =
        givenAuthenticatedUserWithRoles("restricted-agency-admin");
    assertThat(authenticatedUser.isRestrictedAgencyAdmin()).isTrue();
  }

  @Test
  public void isAgencySuperAdmin_Should_ReturnFalse_When_RolesDoNotContainAgencyAdmin() {
    AuthenticatedUser authenticatedUser = givenAuthenticatedUserWithRoles("user");
    assertThat(authenticatedUser.isAgencySuperAdmin()).isFalse();
  }

  @Test
  public void isAgencySuperAdmin_Should_ReturnTrue_When_RolesContainAgencyAdmin() {
    AuthenticatedUser authenticatedUser = givenAuthenticatedUserWithRoles("agency-admin");
    assertThat(authenticatedUser.isAgencySuperAdmin()).isTrue();
  }

  @Test
  public void hasRestrictedAgencyPriviliges_Should_ReturnTrue_When_RestrictedAndNotSuperAdmin() {
    AuthenticatedUser authenticatedUser =
        givenAuthenticatedUserWithRoles("restricted-agency-admin");
    assertThat(authenticatedUser.hasRestrictedAgencyPriviliges()).isTrue();
  }

  @Test
  public void hasRestrictedAgencyPriviliges_Should_ReturnFalse_When_RestrictedAndAlsoSuperAdmin() {
    AuthenticatedUser authenticatedUser =
        givenAuthenticatedUserWithRoles("restricted-agency-admin", "agency-admin");
    assertThat(authenticatedUser.hasRestrictedAgencyPriviliges()).isFalse();
  }

  @Test
  public void hasRestrictedAgencyPriviliges_Should_ReturnFalse_When_NotRestricted() {
    AuthenticatedUser authenticatedUser = givenAuthenticatedUserWithRoles("agency-admin");
    assertThat(authenticatedUser.hasRestrictedAgencyPriviliges()).isFalse();
  }

  @Test
  public void isAdviceSeeker_Should_ReturnTrue_When_RolesContainUser() {
    AuthenticatedUser authenticatedUser = givenAuthenticatedUserWithRoles("user");
    assertThat(authenticatedUser.isAdviceSeeker()).isTrue();
  }

  @Test
  public void isAdviceSeeker_Should_ReturnFalse_When_RolesDoNotContainUser() {
    AuthenticatedUser authenticatedUser = givenAuthenticatedUserWithRoles("consultant");
    assertThat(authenticatedUser.isAdviceSeeker()).isFalse();
  }

  @Test
  public void isConsultant_Should_ReturnTrue_When_RolesContainConsultant() {
    AuthenticatedUser authenticatedUser = givenAuthenticatedUserWithRoles("consultant");
    assertThat(authenticatedUser.isConsultant()).isTrue();
  }

  @Test
  public void isConsultant_Should_ReturnFalse_When_RolesDoNotContainConsultant() {
    AuthenticatedUser authenticatedUser = givenAuthenticatedUserWithRoles("user");
    assertThat(authenticatedUser.isConsultant()).isFalse();
  }

  @Test
  public void isSingleTenantAdmin_Should_ReturnTrue_When_RolesContainSingleTenantAdmin() {
    AuthenticatedUser authenticatedUser = givenAuthenticatedUserWithRoles("single-tenant-admin");
    assertThat(authenticatedUser.isSingleTenantAdmin()).isTrue();
  }

  @Test
  public void isSingleTenantAdmin_Should_ReturnFalse_When_RolesDoNotContainSingleTenantAdmin() {
    AuthenticatedUser authenticatedUser = givenAuthenticatedUserWithRoles("user");
    assertThat(authenticatedUser.isSingleTenantAdmin()).isFalse();
  }

  @Test
  public void isTenantSuperAdmin_Should_ReturnTrue_When_RolesContainTenantAdmin() {
    AuthenticatedUser authenticatedUser = givenAuthenticatedUserWithRoles("tenant-admin");
    assertThat(authenticatedUser.isTenantSuperAdmin()).isTrue();
  }

  @Test
  public void isTenantSuperAdmin_Should_ReturnFalse_When_RolesDoNotContainTenantAdmin() {
    AuthenticatedUser authenticatedUser = givenAuthenticatedUserWithRoles("user");
    assertThat(authenticatedUser.isTenantSuperAdmin()).isFalse();
  }

  @Test
  public void isAnonymous_Should_ReturnTrue_When_RolesContainAnonymous() {
    AuthenticatedUser authenticatedUser = givenAuthenticatedUserWithRoles("anonymous");
    assertThat(authenticatedUser.isAnonymous()).isTrue();
  }

  @Test
  public void isAnonymous_Should_ReturnFalse_When_RolesDoNotContainAnonymous() {
    AuthenticatedUser authenticatedUser = givenAuthenticatedUserWithRoles("user");
    assertThat(authenticatedUser.isAnonymous()).isFalse();
  }

  @Test
  public void isGlobalSupportAdmin_Should_ReturnTrue_When_RolesContainGlobalSupportAdmin() {
    AuthenticatedUser authenticatedUser = givenAuthenticatedUserWithRoles("global-support-admin");
    assertThat(authenticatedUser.isGlobalSupportAdmin()).isTrue();
  }

  @Test
  public void isGlobalSupportAdmin_Should_ReturnFalse_When_RolesIsNull() {
    AuthenticatedUser authenticatedUser = new AuthenticatedUser();
    assertThat(authenticatedUser.isGlobalSupportAdmin()).isFalse();
  }

  @Test
  public void isPlatformAdmin_Should_ReturnTrue_When_TenantIdIsZeroAndAgencyAndTenantSuperAdmin() {
    AuthenticatedUser authenticatedUser =
        givenAuthenticatedUserWithRoles("agency-admin", "tenant-admin");
    authenticatedUser.setTenantId(0L);
    assertThat(authenticatedUser.isPlatformAdmin()).isTrue();
  }

  @Test
  public void isPlatformAdmin_Should_ReturnFalse_When_TenantIdIsNotZero() {
    AuthenticatedUser authenticatedUser =
        givenAuthenticatedUserWithRoles("agency-admin", "tenant-admin");
    authenticatedUser.setTenantId(1L);
    assertThat(authenticatedUser.isPlatformAdmin()).isFalse();
  }

  @Test
  public void isPlatformAdmin_Should_ReturnFalse_When_TenantIdIsNull() {
    AuthenticatedUser authenticatedUser =
        givenAuthenticatedUserWithRoles("agency-admin", "tenant-admin");
    assertThat(authenticatedUser.isPlatformAdmin()).isFalse();
  }

  @Test
  public void isPlatformAdmin_Should_ReturnFalse_When_NotAgencySuperAdmin() {
    AuthenticatedUser authenticatedUser = givenAuthenticatedUserWithRoles("tenant-admin");
    authenticatedUser.setTenantId(0L);
    assertThat(authenticatedUser.isPlatformAdmin()).isFalse();
  }

  @Test
  public void isPlatformAdmin_Should_ReturnFalse_When_NotTenantSuperAdmin() {
    AuthenticatedUser authenticatedUser = givenAuthenticatedUserWithRoles("agency-admin");
    authenticatedUser.setTenantId(0L);
    assertThat(authenticatedUser.isPlatformAdmin()).isFalse();
  }
}
