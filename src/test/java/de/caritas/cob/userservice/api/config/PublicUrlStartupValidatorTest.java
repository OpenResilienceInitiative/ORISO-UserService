package de.caritas.cob.userservice.api.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** ORISO-Helm#368: every public origin a mail links to is required, absolute and real. */
class PublicUrlStartupValidatorTest {

  private MockEnvironment environment;

  @BeforeEach
  void setUp() {
    environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    environment.setProperty("app.base.url", "https://app.counselling.test");
    environment.setProperty(
        "system.notification.frontend.base-url", "https://app.counselling.test");
    environment.setProperty("dpa.sign.frontend.base-url", "https://app.counselling.test");
    environment.setProperty("magic.link.frontend.base-url", "https://app.counselling.test");
    environment.setProperty("account.invite.app.frontend.base-url", "https://app.counselling.test");
    environment.setProperty(
        "account.invite.admin.frontend.base-url", "https://admin.counselling.test");
  }

  @Test
  void acceptsAbsoluteOriginsForEveryRequiredVariable() {
    assertThatCode(() -> PublicUrlStartupValidator.validate(environment))
        .doesNotThrowAnyException();
  }

  @Test
  void namesEveryMissingVariableAtOnce() {
    environment.setProperty("app.base.url", "");
    environment.setProperty("magic.link.frontend.base-url", "${MAGIC_LINK_FRONTEND_BASE_URL}");
    environment.setProperty("account.invite.admin.frontend.base-url", "  ");

    assertThatThrownBy(() -> PublicUrlStartupValidator.validate(environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be set (APP_BASE_URL)")
        .hasMessageContaining("must be set (MAGIC_LINK_FRONTEND_BASE_URL)")
        .hasMessageContaining("must be set (ACCOUNT_INVITE_ADMIN_FRONTEND_BASE_URL)")
        .hasMessageNotContaining("DPA_SIGN_FRONTEND_BASE_URL");
  }

  @Test
  void rejectsRelativeSchemelessAndNonHttpValues() {
    environment.setProperty("dpa.sign.frontend.base-url", "/app");
    environment.setProperty("system.notification.frontend.base-url", "app.counselling.test");
    environment.setProperty("account.invite.app.frontend.base-url", "mailto:ops@counselling.test");

    assertThatThrownBy(() -> PublicUrlStartupValidator.validate(environment))
        .hasMessageContaining("DPA_SIGN_FRONTEND_BASE_URL")
        .hasMessageContaining("SYSTEM_NOTIFICATION_FRONTEND_BASE_URL")
        .hasMessageContaining("ACCOUNT_INVITE_APP_FRONTEND_BASE_URL");
  }

  @Test
  void rejectsAQueryBecauseRoutesAreAppendedToTheOrigin() {
    environment.setProperty("magic.link.frontend.base-url", "https://app.counselling.test?x=1");

    assertThatThrownBy(() -> PublicUrlStartupValidator.validate(environment))
        .hasMessageContaining("MAGIC_LINK_FRONTEND_BASE_URL");
  }

  @Test
  void rejectsAFragmentBecauseRoutesAreAppendedToTheOrigin() {
    environment.setProperty(
        "magic.link.frontend.base-url", "https://app.counselling.test/#section");

    assertThatThrownBy(() -> PublicUrlStartupValidator.validate(environment))
        .hasMessageContaining("MAGIC_LINK_FRONTEND_BASE_URL");
  }

  @Test
  void rejectsUserInfoInTheOrigin() {
    environment.setProperty("dpa.sign.frontend.base-url", "https://someone@app.counselling.test");

    assertThatThrownBy(() -> PublicUrlStartupValidator.validate(environment))
        .hasMessageContaining("DPA_SIGN_FRONTEND_BASE_URL")
        .hasMessageContaining("user info");
  }

  @Test
  void keepsAPathPrefixBecauseTheAdminResetLinkLivesUnderSlashAdmin() {
    environment.setProperty(
        "password.reset.admin.frontend.base-url", "https://app.counselling.test/admin");

    assertThatCode(() -> PublicUrlStartupValidator.validate(environment))
        .doesNotThrowAnyException();
  }

  @Test
  void namesTheVariableAndTheReasonForATemplateHost() {
    environment.setProperty("magic.link.frontend.base-url", "https://app.example.org");

    assertThatThrownBy(() -> PublicUrlStartupValidator.validate(environment))
        .hasMessageContaining("MAGIC_LINK_FRONTEND_BASE_URL")
        .hasMessageContaining("reserved example or template host")
        .hasMessageContaining("app.example.org");
  }

  @Test
  void rejectsTemplatePlaceholdersInDeployedProfiles() {
    environment.setProperty(
        "account.invite.app.frontend.base-url", "https://your-domain.example.com");
    environment.setProperty("dpa.sign.frontend.base-url", "https://app.example.com");

    assertThatThrownBy(() -> PublicUrlStartupValidator.validate(environment))
        .hasMessageContaining("ACCOUNT_INVITE_APP_FRONTEND_BASE_URL")
        .hasMessageContaining("DPA_SIGN_FRONTEND_BASE_URL")
        .hasMessageContaining("reserved example or template host");
  }

  @Test
  void rejectsLoopbackHostsInDeployedProfiles() {
    environment.setProperty("app.base.url", "https://localhost");
    environment.setProperty("magic.link.frontend.base-url", "https://127.0.0.1");
    environment.setProperty("password.reset.frontend.base-url", "https://app.localhost");

    assertThatThrownBy(() -> PublicUrlStartupValidator.validate(environment))
        .hasMessageContaining("APP_BASE_URL")
        .hasMessageContaining("MAGIC_LINK_FRONTEND_BASE_URL")
        .hasMessageContaining("PASSWORD_RESET_FRONTEND_BASE_URL")
        .hasMessageContaining("loopback");
  }

  @Test
  void rejectsIntegerIpv4AliasThatBrowsersInterpretAsLoopback() {
    environment.setProperty("magic.link.frontend.base-url", "https://2130706433");

    assertThatThrownBy(() -> PublicUrlStartupValidator.validate(environment))
        .hasMessageContaining("MAGIC_LINK_FRONTEND_BASE_URL")
        .hasMessageContaining("numeric IP alias");
  }

  @Test
  void rejectsTrailingDotLocalhost() {
    environment.setProperty("app.base.url", "https://localhost.");

    assertThatThrownBy(() -> PublicUrlStartupValidator.validate(environment))
        .hasMessageContaining("APP_BASE_URL");
  }

  @Test
  void rejectsHexIpv4Alias() {
    environment.setProperty("magic.link.frontend.base-url", "https://0x7f000001");

    assertThatThrownBy(() -> PublicUrlStartupValidator.validate(environment))
        .hasMessageContaining("MAGIC_LINK_FRONTEND_BASE_URL");
  }

  @Test
  void allowsExampleHostsInTheLocalAndTestingProfiles() {
    environment.setActiveProfiles("testing");
    environment.setProperty("dpa.sign.frontend.base-url", "https://app.example.com");

    assertThatCode(() -> PublicUrlStartupValidator.validate(environment))
        .doesNotThrowAnyException();
  }

  @Test
  void keepsLocalhostWorkingForTheLocalComposeStackOnTheLocalProfile() {
    environment.setActiveProfiles("local");
    environment.setProperty("app.base.url", "http://localhost:9002");

    assertThatCode(() -> PublicUrlStartupValidator.validate(environment))
        .doesNotThrowAnyException();
  }

  @Test
  void leavesPasswordResetOptionalButRejectsAMalformedValue() {
    environment.setProperty("password.reset.frontend.base-url", "");
    assertThatCode(() -> PublicUrlStartupValidator.validate(environment))
        .doesNotThrowAnyException();

    environment.setProperty(
        "password.reset.admin.frontend.base-url", "admin.counselling.test/admin");
    assertThatThrownBy(() -> PublicUrlStartupValidator.validate(environment))
        .hasMessageContaining("PASSWORD_RESET_ADMIN_FRONTEND_BASE_URL");
  }
}
