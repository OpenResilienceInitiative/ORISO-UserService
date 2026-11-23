package de.caritas.cob.userservice.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
  void rejectsPrivateIpv4HostsInDeployedProfiles() {
    for (String host :
        new String[] {
          "10.0.0.1", "10.255.255.254", "169.254.0.1", "169.254.169.254",
          "169.254.255.254", "172.16.0.1", "172.31.255.254", "192.168.1.1"
        }) {
      environment.setProperty("magic.link.frontend.base-url", "https://" + host);

      assertThatThrownBy(() -> PublicUrlStartupValidator.validate(environment))
          .hasMessageContaining("MAGIC_LINK_FRONTEND_BASE_URL")
          .hasMessageContaining("public host");
    }
  }

  @Test
  void rejectsReservedIpv4OriginsIncludingTheEdgesOfEachRange() {
    for (String host :
        new String[] {
          "0.1.2.3",
          "0.255.255.255",
          "100.64.0.0",
          "100.127.255.255",
          "127.255.255.254",
          "192.0.0.0",
          "192.0.0.8",
          "192.0.0.170",
          "192.0.0.171",
          "192.0.2.0",
          "192.0.2.255",
          "192.88.99.2",
          "198.18.0.0",
          "198.19.255.255",
          "198.51.100.0",
          "198.51.100.255",
          "203.0.113.0",
          "203.0.113.255",
          "224.0.0.0",
          "239.255.255.255",
          "240.0.0.0",
          "255.255.255.255"
        }) {
      environment.setProperty("magic.link.frontend.base-url", "https://" + host);

      assertThatThrownBy(() -> PublicUrlStartupValidator.validate(environment))
          .as("deployed mail origin %s", host)
          .hasMessageContaining("MAGIC_LINK_FRONTEND_BASE_URL")
          .hasMessageContaining("public host");
    }
  }

  @Test
  void allowsPublicIpv4AddressesAdjacentToReservedRanges() {
    for (String host :
        new String[] {
          "100.63.255.255",
          "100.128.0.0",
          "192.0.0.9",
          "192.0.0.10",
          "192.0.1.255",
          "192.0.3.0",
          "198.17.255.255",
          "198.20.0.0",
          "198.51.99.255",
          "198.51.101.0",
          "203.0.112.255",
          "203.0.114.0",
          "223.255.255.254"
        }) {
      environment.setProperty("magic.link.frontend.base-url", "https://" + host);

      assertThatCode(() -> PublicUrlStartupValidator.validate(environment))
          .as("deployed mail origin %s", host)
          .doesNotThrowAnyException();
    }
  }

  @Test
  void appliesTheSameReservedIpv4RangesToMappedIpv6WithoutChangingLocalOrigins() {
    for (String host :
        new String[] {"[::ffff:100.64.0.1]", "[::ffff:192.0.2.1]", "[::ffff:203.0.113.5]"}) {
      environment.setProperty("magic.link.frontend.base-url", "https://" + host);

      assertThatThrownBy(() -> PublicUrlStartupValidator.validate(environment))
          .as("deployed mapped mail origin %s", host)
          .hasMessageContaining("MAGIC_LINK_FRONTEND_BASE_URL");
    }

    environment.setProperty("magic.link.frontend.base-url", "https://[::ffff:203.0.114.5]");
    assertThatCode(() -> PublicUrlStartupValidator.validate(environment))
        .doesNotThrowAnyException();

    environment.setActiveProfiles("local");
    environment.setProperty("magic.link.frontend.base-url", "http://[::ffff:100.64.0.1]");
    assertThatCode(() -> PublicUrlStartupValidator.validate(environment))
        .doesNotThrowAnyException();
  }

  @Test
  void keepsPublicIpv4HostsAndLocalProfilePrivateOriginsAvailable() {
    for (String host : new String[] {"10.255.255.254", "172.15.255.254", "172.32.0.1"}) {
      environment.setProperty("magic.link.frontend.base-url", "https://" + host);
      environment.setActiveProfiles("local");

      assertThatCode(() -> PublicUrlStartupValidator.validate(environment))
          .doesNotThrowAnyException();
    }

    environment.setActiveProfiles("prod");
    environment.setProperty("magic.link.frontend.base-url", "https://172.15.255.254");
    assertThatCode(() -> PublicUrlStartupValidator.validate(environment))
        .doesNotThrowAnyException();
    environment.setProperty("magic.link.frontend.base-url", "https://172.32.0.1");
    assertThatCode(() -> PublicUrlStartupValidator.validate(environment))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsIpv6LoopbackAndUnspecifiedHostsInDeployedProfiles() {
    for (String host : new String[] {"[::1]", "[0:0:0:0:0:0:0:1]", "[::ffff:127.0.0.1]", "[::]"}) {
      environment.setProperty("magic.link.frontend.base-url", "https://" + host);

      assertThatThrownBy(() -> PublicUrlStartupValidator.validate(environment))
          .hasMessageContaining("MAGIC_LINK_FRONTEND_BASE_URL")
          .hasMessageContaining("loopback");
    }
  }

  @Test
  void rejectsPrivateIpv6HostsInDeployedProfiles() {
    for (String host : new String[] {"[fe80::1]", "[fec0::1]", "[fc00::1]", "[fd12:3456::1]"}) {
      environment.setProperty("magic.link.frontend.base-url", "https://" + host);

      assertThatThrownBy(() -> PublicUrlStartupValidator.validate(environment))
          .hasMessageContaining("MAGIC_LINK_FRONTEND_BASE_URL")
          .hasMessageContaining("public host");
    }
  }

  @Test
  void rejectsDocumentationAndMulticastIpv6HostsInDeployedProfiles() {
    for (String host : new String[] {"[2001:db8::1]", "[2001:db8:ffff::1]", "[ff02::1]"}) {
      environment.setProperty("magic.link.frontend.base-url", "https://" + host);

      assertThatThrownBy(() -> PublicUrlStartupValidator.validate(environment))
          .hasMessageContaining("MAGIC_LINK_FRONTEND_BASE_URL")
          .hasMessageContaining("public host");
    }
  }

  @Test
  void allowsPublicIpv6Hosts() {
    environment.setProperty("magic.link.frontend.base-url", "https://[2001:4860:4860::8888]");

    assertThatCode(() -> PublicUrlStartupValidator.validate(environment))
        .doesNotThrowAnyException();
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
        .hasMessageContaining("APP_BASE_URL")
        .hasMessageContaining("loopback or private host");
  }

  @Test
  void rejectsHexIpv4Alias() {
    environment.setProperty("magic.link.frontend.base-url", "https://0x7f000001");

    assertThatThrownBy(() -> PublicUrlStartupValidator.validate(environment))
        .hasMessageContaining("MAGIC_LINK_FRONTEND_BASE_URL")
        .hasMessageContaining("numeric IP alias");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "127.1",
        "10.1",
        "0177.0.0.1",
        "012.0.0.1",
        "0300.0250.0.1",
        "0x7f.0.0.1",
        "0xc0.0xa8.0.0x1",
        "127.00.0.1",
        "127.0.0.1.",
        "2130706433",
        "0x7f000001",
        "8.8.8.8.",
        "008.008.008.008"
      })
  void rejectsNoncanonicalNumericHostsWithAnExplicitReason(String host) {
    environment.setProperty("magic.link.frontend.base-url", "https://" + host);

    assertThatThrownBy(() -> PublicUrlStartupValidator.validate(environment))
        .hasMessageContaining("MAGIC_LINK_FRONTEND_BASE_URL")
        .hasMessageContaining("numeric IP alias");
  }

  @ParameterizedTest
  @ValueSource(strings = {"localhost.", "app.localhost.", "app.example.org.", "app.example.net."})
  void trailingRootDotCannotHideANonPublicDnsHost(String host) {
    environment.setProperty("app.base.url", "https://" + host);

    assertThatThrownBy(() -> PublicUrlStartupValidator.validate(environment))
        .hasMessageContaining("APP_BASE_URL")
        .hasMessageContaining("reserved example or template host");
  }

  @Test
  void acceptsAPublicFullyQualifiedDnsNameWithoutChangingTheConfiguredOrigin() {
    String configured = "https://service.counselling.test.:8443/admin";
    environment.setProperty("account.invite.admin.frontend.base-url", configured);

    assertThatCode(() -> PublicUrlStartupValidator.validate(environment))
        .doesNotThrowAnyException();
    assertThat(environment.getProperty("account.invite.admin.frontend.base-url"))
        .isEqualTo(configured);
  }

  @ParameterizedTest
  @ValueSource(strings = {"8.8.8.8", "172.15.255.254", "172.32.0.1"})
  void acceptsCanonicalPublicIpv4(String host) {
    environment.setProperty("magic.link.frontend.base-url", "https://" + host);

    assertThatCode(() -> PublicUrlStartupValidator.validate(environment))
        .doesNotThrowAnyException();
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
