package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Validation runs without opening a connection, including for hostile JDBC authorities. */
class InviteTemplateDeleteDatabaseSafetyTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbc:mariadb://localhost/template_delete_test",
        "jdbc:mariadb://127.0.0.1/template_delete_test",
        "jdbc:mariadb://localhost:3306/template_delete_test",
        "jdbc:mariadb://127.0.0.1:3339/template_delete_test"
      })
  void allowsOnlyExplicitDisposableLoopbackDatabase(String url) {
    assertThat(
            InviteTemplateDeleteGuardMigrationTest.validateExternalSettings(
                url, "test-user", "test-password"))
        .isEqualTo(url);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbc:mariadb://localhost/userservice",
        "jdbc:mariadb://localhost/template_delete_test_extra",
        "jdbc:mariadb://localhost/template_delete_test/userservice",
        "jdbc:mariadb://localhost/template_delete_test/",
        "jdbc:mariadb://production.example/template_delete_test",
        "jdbc:mariadb://127.0.0.2/template_delete_test",
        "jdbc:mariadb://localhost.example/template_delete_test",
        "jdbc:mariadb://localhost./template_delete_test",
        "jdbc:mariadb://127.0.0.1,production.example/template_delete_test",
        "jdbc:mariadb://production.example@localhost/template_delete_test",
        "jdbc:mariadb://localhost@production.example/template_delete_test",
        "jdbc:mariadb://localhost/template_delete_test?sessionVariables=foreign_key_checks=0",
        "jdbc:mariadb://localhost/template_delete_test?",
        "jdbc:mariadb://localhost/template_delete_test#userservice",
        "jdbc:mariadb://localhost/%74emplate_delete_test",
        "jdbc:mariadb://localhost/%2Ftemplate_delete_test",
        "jdbc:mariadb://localhost:0/template_delete_test",
        "jdbc:mariadb://localhost:65536/template_delete_test",
        "jdbc:mariadb://localhost:bad/template_delete_test",
        "jdbc:mariadb:sequential://localhost/template_delete_test",
        "jdbc:mysql://localhost/template_delete_test",
        "jdbc:h2:mem:template_delete_test",
        "",
        "not a JDBC URL"
      })
  void rejectsUnsafeUrlWithoutConnecting(String url) {
    assertThatThrownBy(
            () ->
                InviteTemplateDeleteGuardMigrationTest.validateExternalSettings(
                    url, "test-user", "test-password"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @MethodSource("missingCredentials")
  void requiresExplicitExternalCredentials(String user, String password) {
    assertThatThrownBy(
            () ->
                InviteTemplateDeleteGuardMigrationTest.validateExternalSettings(
                    "jdbc:mariadb://localhost/template_delete_test", user, password))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static Stream<Arguments> missingCredentials() {
    return Stream.of(
        Arguments.of(null, "test-password"), Arguments.of("", "test-password"),
        Arguments.of(" ", "test-password"), Arguments.of("test-user", null),
        Arguments.of("test-user", ""), Arguments.of("test-user", " "));
  }
}
