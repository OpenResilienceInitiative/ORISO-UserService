package de.caritas.cob.userservice.api.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigurationValidatorTest {

  private ConfigurationValidator validator;

  @BeforeEach
  void setUp() {
    validator = new ConfigurationValidator();
    givenAllRequiredConfigurationValues();
  }

  @Test
  void validateConfigurationShouldPassWhenAllRequiredValuesArePresent() {
    assertThatCode(validator::validateConfiguration).doesNotThrowAnyException();
  }

  @Test
  void validateConfigurationShouldRejectMissingIdentityTechnicalClientId() {
    setField(validator, "identityTechnicalClientId", "");

    assertThatThrownBy(validator::validateConfiguration)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("identity.technical-user.client-id (IDENTITY_TECHNICAL_CLIENT_ID)");
  }

  @Test
  void validateConfigurationShouldRejectMissingIdentityTechnicalClientSecret() {
    setField(validator, "identityTechnicalClientSecret", " ");

    assertThatThrownBy(validator::validateConfiguration)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "identity.technical-user.client-secret (KEYCLOAK_BACKEND_TECHNICAL_CLIENT_SECRET)");
  }

  /**
   * Epic #351, workstream 3. A missing AGENCY_ADMIN_SERVICE_API_URL used to fall back to this
   * service's own base URL, so the UserService called itself and every consultant-agency read
   * answered 500 at runtime. Startup is the only place where that is cheap to notice.
   */
  @Test
  void validateConfigurationShouldRejectMissingAgencyAdminServiceApiUrl() {
    setField(validator, "agencyAdminServiceApiUrl", "");

    assertThatThrownBy(validator::validateConfiguration)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("agency.admin.service.api.url (AGENCY_ADMIN_SERVICE_API_URL)");
  }

  @Test
  void validateConfigurationShouldRejectMissingAgencyServiceApiUrl() {
    setField(validator, "agencyServiceApiUrl", " ");

    assertThatThrownBy(validator::validateConfiguration)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("agency.service.api.url (AGENCY_SERVICE_API_URL)");
  }

  @Test
  void validateConfigurationShouldReportEveryMissingValueAtOnce() {
    setField(validator, "agencyServiceApiUrl", "");
    setField(validator, "agencyAdminServiceApiUrl", "");
    setField(validator, "tenantServiceApiUrl", "");

    assertThatThrownBy(validator::validateConfiguration)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("agency.service.api.url")
        .hasMessageContaining("agency.admin.service.api.url")
        .hasMessageContaining("tenant.service.api.url");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  void validateConfigurationShouldAllowAbsentScopedTopicServiceApiUrl(String value) {
    setField(validator, "scopedTopicServiceApiUrl", value);

    assertThatCode(validator::validateConfiguration).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://consultingtypeservice:8080",
        "https://catalogue-service:8443/prefix",
        "http://localhost:8083"
      })
  void validateConfigurationShouldAllowInternalScopedTopicServiceApiUrl(String value) {
    setField(validator, "scopedTopicServiceApiUrl", value);

    assertThatCode(validator::validateConfiguration).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://consultingtypeservice.dev:8080",
        "https://topics.example.org",
        "http://consultingtypeservice.:8080",
        "http://127.0.0.1:8083",
        "http://[::1]:8083",
        "http://2130706433",
        "ftp://catalogue-service",
        "/topic/public",
        "http:/catalogue-service",
        "http://invalid_host",
        "http://catalogue-service:65536",
        "http://catalogue-service:",
        "http://catalogue-service?q=on",
        "http://catalogue-service#fragment",
        "http://fake-credential@catalogue-service",
        "http://catalogue-service/bad path",
        " http://catalogue-service"
      })
  void validateConfigurationShouldRejectInvalidScopedTopicServiceApiUrl(String value) {
    setField(validator, "scopedTopicServiceApiUrl", value);

    assertThatThrownBy(validator::validateConfiguration)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "consulting.type.service.scoped.api.url (CONSULTING_TYPE_SERVICE_SCOPED_API_URL)")
        .hasMessageNotContaining(value);
  }

  private void givenAllRequiredConfigurationValues() {
    setField(validator, "datasourceUrl", "jdbc:mariadb://mariadb/userservice");
    setField(validator, "datasourceUsername", "userservice");
    setField(validator, "datasourcePassword", "secret");
    setField(validator, "keycloakAuthServerUrl", "https://auth.example");
    setField(validator, "keycloakRealm", "online-beratung");
    setField(validator, "identityOpenIdConnectUrl", "https://auth.example/openid-connect");
    setField(validator, "identityTechnicalClientId", "backend-technical");
    setField(validator, "identityTechnicalClientSecret", "secret");
    setField(validator, "consultingTypeServiceApiUrl", "https://consulting-type.example/service");
    setField(validator, "tenantServiceApiUrl", "https://tenant.example/service");
    setField(validator, "agencyServiceApiUrl", "https://agency.example/service");
    setField(validator, "agencyAdminServiceApiUrl", "https://agency.example");
    setField(validator, "matrixApiUrl", "https://matrix.example");
    setField(validator, "matrixRegistrationSharedSecret", "secret");
  }
}
