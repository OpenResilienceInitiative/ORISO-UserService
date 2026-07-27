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

  @Test
  void identityPolicyShouldUseConfiguredOtpRolePermission() {
    givenAValidIdentityConfig();
    identityConfig.setOtpAllowedForUsers(true);

    assertTrue(identityConfig.isTwoFactorAuthenticationAllowed(Set.of(UserRole.USER.getValue())));
  }

  @Test
  void identityPolicyShouldUseConfiguredConsultantDisplayNamePermission() {
    givenAValidIdentityConfig();
    identityConfig.setDisplayNameAllowedForConsultants(true);

    assertTrue(identityConfig.isConsultantDisplayNameAllowed());
  }

  @Test
  void identityPolicyShouldAcceptOnlyNonDummyProfileEmailsForMagicLink() {
    givenAValidIdentityConfig();
    identityConfig.setEmailDummySuffix("@dummy.invalid");

    assertTrue(identityConfig.isProfileEmailUsableForMagicLink("person@example.org"));
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink(null));
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink(""));
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink("   "));
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink("person@dummy.invalid"));
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
