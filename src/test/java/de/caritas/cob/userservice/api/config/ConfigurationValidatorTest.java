package de.caritas.cob.userservice.api.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
  void validateConfigurationShouldRejectMissingIdentityTechnicalUsername() {
    setField(validator, "identityTechnicalUsername", "");

    assertThatThrownBy(validator::validateConfiguration)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "identity.technical-user.username (IDENTITY_TECHNICAL_USER_USERNAME)");
  }

  @Test
  void validateConfigurationShouldRejectMissingIdentityTechnicalPassword() {
    setField(validator, "identityTechnicalPassword", " ");

    assertThatThrownBy(validator::validateConfiguration)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "identity.technical-user.password (IDENTITY_TECHNICAL_USER_PASSWORD)");
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

  private void givenAllRequiredConfigurationValues() {
    setField(validator, "datasourceUrl", "jdbc:mariadb://mariadb/userservice");
    setField(validator, "datasourceUsername", "userservice");
    setField(validator, "datasourcePassword", "secret");
    setField(validator, "keycloakAuthServerUrl", "https://auth.example");
    setField(validator, "keycloakRealm", "online-beratung");
    setField(validator, "identityOpenIdConnectUrl", "https://auth.example/openid-connect");
    setField(validator, "identityTechnicalUsername", "technical-user");
    setField(validator, "identityTechnicalPassword", "secret");
    setField(validator, "consultingTypeServiceApiUrl", "https://consulting-type.example/service");
    setField(validator, "tenantServiceApiUrl", "https://tenant.example/service");
    setField(validator, "agencyServiceApiUrl", "https://agency.example/service");
    setField(validator, "agencyAdminServiceApiUrl", "https://agency.example");
    setField(validator, "matrixApiUrl", "https://matrix.example");
    setField(validator, "matrixRegistrationSharedSecret", "secret");
  }
}
