package de.caritas.cob.userservice.api.config.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.lang3.RandomStringUtils;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdentityConfigTest {

  private static final EasyRandom easyRandom = new EasyRandom();
  private static Validator validator;
  private static Locale defaultLocale;

  private IdentityConfig identityConfig;
  private Set<ConstraintViolation<IdentityConfig>> violations;

  @BeforeAll
  static void setup() {
    defaultLocale = Locale.getDefault();
    Locale.setDefault(Locale.ENGLISH);
    validator = Validation.buildDefaultValidatorFactory().getValidator();
  }

  @AfterAll
  static void teardownAll() {
    Locale.setDefault(defaultLocale);
  }

  @AfterEach
  void teardownEach() {
    identityConfig = null;
    violations = null;
  }

  @Test
  void isProfileEmailUsableForMagicLinkShouldRejectDummyNullAndBlankAddresses() {
    givenAValidIdentityConfig();
    identityConfig.setEmailDummySuffix("@dummy.oriso.org");

    // The rule the web layer used to inline; owning it here gives it one definition.
    assertTrue(identityConfig.isProfileEmailUsableForMagicLink("real.user@example.org"));
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink("u123@dummy.oriso.org"));
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink(null));
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink(""));
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink("   "));
  }

  @Test
  void isProfileEmailUsableForMagicLinkShouldRejectDummyAddressesRegardlessOfCaseAndWhitespace() {
    givenAValidIdentityConfig();
    identityConfig.setEmailDummySuffix("@dummy.oriso.org");

    // A dummy address must stay unusable even when it arrives un-normalized.
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink("u123@dummy.oriso.org "));
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink("  u123@dummy.oriso.org"));
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink("u123@DUMMY.ORISO.ORG"));
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink("  u123@Dummy.Oriso.Org  "));
    assertTrue(identityConfig.isProfileEmailUsableForMagicLink(" real.user@example.org "));
  }

  @Test
  void isProfileEmailUsableForMagicLinkShouldNormalizeTheConfiguredSuffixItself() {
    givenAValidIdentityConfig();
    // The configured side must be normalized too, not only the submitted address.
    identityConfig.setEmailDummySuffix(" @DUMMY.oriso.org ");

    assertFalse(identityConfig.isProfileEmailUsableForMagicLink("u123@dummy.oriso.org"));
    assertTrue(identityConfig.isProfileEmailUsableForMagicLink("real.user@example.org"));
  }

  @Test
  void isProfileEmailUsableForMagicLinkShouldRejectDummyAddressesWithUnicodeWhitespace() {
    givenAValidIdentityConfig();
    identityConfig.setEmailDummySuffix("@dummy.oriso.org");

    // hasText() accepts Unicode whitespace, so normalization must strip it too.
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink("u123@dummy.oriso.org\u2003"));
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink("\u00a0u123@dummy.oriso.org"));
  }

  @Test
  void isProfileEmailUsableForMagicLinkShouldStayLocaleIndependent_WhenDefaultLocaleIsTurkish() {
    givenAValidIdentityConfig();
    identityConfig.setEmailDummySuffix("@DUMMY.orIso.org");

    // Turkish lowercasing maps I to a dotless i; Locale.ROOT must keep the
    // comparison stable regardless of the JVM default locale.
    java.util.Locale previous = java.util.Locale.getDefault();
    java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
    try {
      assertFalse(identityConfig.isProfileEmailUsableForMagicLink("u123@dummy.oriso.org"));
      assertTrue(identityConfig.isProfileEmailUsableForMagicLink("real.user@example.org"));
    } finally {
      java.util.Locale.setDefault(previous);
    }
  }

  @Test
  void isProfileEmailUsableForMagicLinkShouldRejectEverything_WhenNoDummySuffixIsConfigured() {
    givenAValidIdentityConfig();
    // Without a configured suffix the dummy rule cannot be evaluated: fail closed.
    identityConfig.setEmailDummySuffix(null);

    assertFalse(identityConfig.isProfileEmailUsableForMagicLink("real.user@example.org"));
  }

  @Test
  void isConsultantDisplayNameAllowedShouldReadTheConfiguredFlagNullSafely() {
    givenAValidIdentityConfig();

    identityConfig.setDisplayNameAllowedForConsultants(true);
    assertTrue(identityConfig.isConsultantDisplayNameAllowed());

    identityConfig.setDisplayNameAllowedForConsultants(false);
    assertFalse(identityConfig.isConsultantDisplayNameAllowed());

    // Boxed Boolean: an unset flag must not throw on unboxing.
    identityConfig.setDisplayNameAllowedForConsultants(null);
    assertFalse(identityConfig.isConsultantDisplayNameAllowed());
  }

  @Test
  void isTwoFactorAuthenticationAllowedShouldMirrorTheOtpRolePolicy() {
    givenAValidIdentityConfig();
    identityConfig.setOtpAllowedForUsers(true);
    identityConfig.setOtpAllowedForConsultants(false);

    var users = Set.of(UserRole.USER.getValue());
    var consultants = Set.of(UserRole.CONSULTANT.getValue());

    // The port is a rename of the existing rule, not a second implementation of it.
    assertTrue(identityConfig.isTwoFactorAuthenticationAllowed(users));
    assertEquals(
        identityConfig.isOtpAllowed(users), identityConfig.isTwoFactorAuthenticationAllowed(users));
    assertFalse(identityConfig.isTwoFactorAuthenticationAllowed(consultants));
    assertEquals(
        identityConfig.isOtpAllowed(consultants),
        identityConfig.isTwoFactorAuthenticationAllowed(consultants));
  }

  @Test
  void shouldFindNoViolationsOnValidConfig() {
    givenAValidIdentityConfig();

    violations = validator.validate(identityConfig);

    assertTrue(violations.isEmpty());
  }

  @Test
  void emailDummySuffixShouldRejectMissingCommercialAtSymbol() {
    givenAValidIdentityConfig();
    identityConfig.setEmailDummySuffix(RandomStringUtils.randomAlphanumeric(8));

    violations = validator.validate(identityConfig);

    assertValidationError("emailDummySuffix", "must match \"^@\\S+$\"");
  }

  @Test
  void emailDummySuffixShouldRejectCommercialAtSymbolOnly() {
    givenAValidIdentityConfig();
    identityConfig.setEmailDummySuffix("@");

    violations = validator.validate(identityConfig);

    assertValidationError("emailDummySuffix", "must match \"^@\\S+$\"");
  }

  @Test
  void technicalUserShouldRejectNull() {
    givenAValidIdentityConfig();
    identityConfig.setTechnicalUser(null);

    violations = validator.validate(identityConfig);

    assertValidationError("technicalUser", "must not be null");
  }

  private void givenAValidIdentityConfig() {
    identityConfig = easyRandom.nextObject(IdentityConfig.class);
    identityConfig.setOpenidConnectUrl("https://localhost:1000");
    identityConfig.setOtpUrl("https://localhost:2000");
    identityConfig.setEmailDummySuffix("@localhost:3000");
  }

  private void assertValidationError(String property, String message) {
    assertEquals(1, violations.size());
    var violation = violations.iterator().next();
    assertEquals(property, violation.getPropertyPath().toString());
    assertEquals(message, violation.getMessage());
  }
}
