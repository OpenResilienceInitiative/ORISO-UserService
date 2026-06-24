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

  private void givenAllRequiredConfigurationValues() {
    setField(validator, "datasourceUrl", "jdbc:mariadb://mariadb/userservice");
    setField(validator, "datasourceUsername", "userservice");
    setField(validator, "datasourcePassword", "secret");
    setField(validator, "keycloakAuthServerUrl", "https://auth.example");
    setField(validator, "keycloakRealm", "online-beratung");
    setField(validator, "identityOpenIdConnectUrl", "https://auth.example/openid-connect");
    setField(validator, "identityTechnicalUsername", "technical-user");
    setField(validator, "identityTechnicalPassword", "secret");
    setField(validator, "rocketChatBaseUrl", "https://rocket.example/api/v1");
    setField(validator, "rocketChatMongoUrl", "mongodb://mongodb/rocketchat");
    setField(validator, "rocketTechnicalUsername", "rocket-technical-user");
    setField(validator, "rocketTechnicalPassword", "secret");
    setField(validator, "consultingTypeServiceApiUrl", "https://consulting-type.example/service");
    setField(validator, "tenantServiceApiUrl", "https://tenant.example/service");
    setField(validator, "matrixApiUrl", "https://matrix.example");
    setField(validator, "matrixRegistrationSharedSecret", "secret");
  }
}
