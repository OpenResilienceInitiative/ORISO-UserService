package de.caritas.cob.userservice.api.config.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
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
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

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
    identityConfig.setEmailDummySuffix("@dummy.example.org");

    // The rule the web layer used to inline; owning it here gives it one definition.
    assertTrue(identityConfig.isProfileEmailUsableForMagicLink("real.user@example.org"));
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink("u123@dummy.example.org"));
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink(null));
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink(""));
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink("   "));
  }

  @Test
  void isProfileEmailUsableForMagicLinkShouldRejectDummyAddressesRegardlessOfCaseAndWhitespace() {
    givenAValidIdentityConfig();
    identityConfig.setEmailDummySuffix("@dummy.example.org");

    // A dummy address must stay unusable even when it arrives un-normalized.
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink("u123@dummy.example.org "));
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink("  u123@dummy.example.org"));
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink("u123@DUMMY.EXAMPLE.ORG"));
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink("  u123@Dummy.Example.Org  "));
    assertTrue(identityConfig.isProfileEmailUsableForMagicLink(" real.user@example.org "));
  }

  @Test
  void isProfileEmailUsableForMagicLinkShouldNormalizeTheConfiguredSuffixItself() {
    givenAValidIdentityConfig();
    // The configured side must be normalized too, not only the submitted address.
    identityConfig.setEmailDummySuffix(" @DUMMY.example.org ");

    assertFalse(identityConfig.isProfileEmailUsableForMagicLink("u123@dummy.example.org"));
    assertTrue(identityConfig.isProfileEmailUsableForMagicLink("real.user@example.org"));
  }

  @Test
  void isProfileEmailUsableForMagicLinkShouldRejectDummyAddressesWithUnicodeWhitespace() {
    givenAValidIdentityConfig();
    identityConfig.setEmailDummySuffix("@dummy.example.org");

    // hasText() accepts Unicode whitespace, so normalization must strip it too.
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink("u123@dummy.example.org\u2003"));
    assertFalse(identityConfig.isProfileEmailUsableForMagicLink("\u00a0u123@dummy.example.org"));
  }

  @Test
  void isProfileEmailUsableForMagicLinkShouldStayLocaleIndependent_WhenDefaultLocaleIsTurkish() {
    givenAValidIdentityConfig();
    identityConfig.setEmailDummySuffix("@DUMMY.mIxed.example.org");

    // Turkish lowercasing maps I to a dotless i; Locale.ROOT must keep the
    // comparison stable regardless of the JVM default locale.
    java.util.Locale previous = java.util.Locale.getDefault();
    java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
    try {
      assertFalse(identityConfig.isProfileEmailUsableForMagicLink("u123@dummy.mixed.example.org"));
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

  @Test
  void shippedDefaults_Should_allowOtpForStaffRolesButNotForAdviceSeekers() throws IOException {
    // Every new staff account must set up 2FA. "Allowed" is the precondition: while it is false,
    // enrolment answers 409 and the requirement is not announced, so staff could never comply.
    var sources = new MutablePropertySources();
    new PropertiesPropertySourceLoader()
        .load("application", new ClassPathResource("application.properties"))
        .forEach(sources::addLast);
    var bound =
        new Binder(ConfigurationPropertySources.from(sources))
            .bind(
                "identity",
                org.springframework.boot.context.properties.bind.Bindable.of(IdentityConfig.class))
            .get();

    assertTrue(bound.isOtpAllowed(Set.of(UserRole.CONSULTANT.getValue())));
    assertTrue(bound.isOtpAllowed(Set.of(UserRole.SINGLE_TENANT_ADMIN.getValue())));
    assertTrue(bound.isOtpAllowed(Set.of(UserRole.TENANT_ADMIN.getValue())));
    assertTrue(bound.isOtpAllowed(Set.of(UserRole.RESTRICTED_AGENCY_ADMIN.getValue())));
    assertFalse(bound.isOtpAllowed(Set.of(UserRole.USER.getValue())));
  }
}
