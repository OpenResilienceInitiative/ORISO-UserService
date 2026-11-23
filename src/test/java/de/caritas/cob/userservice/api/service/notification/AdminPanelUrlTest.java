package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The single source both the delivered DPA_SIGNED_NOTICE and its preview read, so a wrong value
 * here is a wrong link in a mail to a Träger administrator.
 *
 * <p>The accepted cases assert the CONSTRUCTED URL rather than merely that no exception was thrown:
 * a validator that returned a constant, or that dropped the {@code /admin} suffix, would satisfy a
 * throws-only test while mailing people a wrong-but-plausible link.
 */
class AdminPanelUrlTest {

  @Test
  void value_Should_appendTheAdminPathToTheConfiguredBase() {
    assertThat(new AdminPanelUrl("https://admin.example.org").value())
        .isEqualTo("https://admin.example.org/admin");
  }

  @Test
  void value_Should_notDoubleTheSeparator_When_theBaseHasATrailingSlash() {
    assertThat(new AdminPanelUrl("https://admin.example.org/").value())
        .isEqualTo("https://admin.example.org/admin");
  }

  @Test
  void value_Should_keepAPortAndContextPath() {
    assertThat(new AdminPanelUrl("http://localhost:8080/oriso").value())
        .isEqualTo("http://localhost:8080/oriso/admin");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://", // scheme only: passes a blank check, mails an unusable link
        "/admin", // relative
        "mailto:admin@example.org", // non-http scheme
        "https://admin.example.org?source=x", // query would swallow the appended path
        "https://admin.example.org/#preview" // fragment would swallow the appended path
      })
  void construction_Should_failFast_ForAUrlThatCannotCarryTheAdminPath(String configured) {
    assertThatThrownBy(() -> new AdminPanelUrl(configured))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void construction_Should_failFast_When_nothingIsConfigured() {
    assertThatThrownBy(() -> new AdminPanelUrl("  ")).isInstanceOf(IllegalStateException.class);
  }
}
