package de.caritas.cob.userservice.api.helper;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.adapters.web.dto.AskerReactivationRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PasswordResetConfirmDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

  private static final String FIFTEEN = "123456789012345";
  private static final String SIXTEEN = "1234567890123456";

  private static Validator validator;

  @BeforeAll
  static void createValidator() {
    validator = Validation.buildDefaultValidatorFactory().getValidator();
  }

  @Test
  void minimumLengthIsSixteen() {
    assertThat(PasswordPolicy.MIN_LENGTH).isEqualTo(16);
  }

  @Test
  void fifteenCharactersAreRejectedAndSixteenAccepted() {
    assertThat(PasswordPolicy.isLongEnough(FIFTEEN)).isFalse();
    assertThat(PasswordPolicy.isLongEnough(SIXTEEN)).isTrue();
    assertThat(PasswordPolicy.isLongEnough(null)).isFalse();
  }

  @Test
  void passwordResetConfirmationEnforcesTheMinimum() {
    assertThat(validator.validate(resetConfirm(FIFTEEN))).isNotEmpty();
    assertThat(validator.validate(resetConfirm(SIXTEEN))).isEmpty();
  }

  @Test
  void askerReactivationEnforcesTheMinimum() {
    assertThat(validator.validate(reactivation(FIFTEEN))).isNotEmpty();
    assertThat(validator.validate(reactivation(SIXTEEN))).isEmpty();
  }

  @Test
  void registrationRejectsAFifteenCharacterPasswordOnThePasswordField() {
    var user = new UserDTO();
    user.setPassword(FIFTEEN);
    assertThat(validator.validateProperty(user, "password")).isNotEmpty();
    user.setPassword(SIXTEEN);
    assertThat(validator.validateProperty(user, "password")).isEmpty();
  }

  @Test
  void generatedPasswordsMeetTheMinimum() {
    var generated = new UserHelper(null, null).getRandomPassword();
    assertThat(PasswordPolicy.isLongEnough(generated)).isTrue();
  }

  private static PasswordResetConfirmDTO resetConfirm(String password) {
    var dto = new PasswordResetConfirmDTO();
    dto.setToken("token");
    dto.setNewPassword(password);
    return dto;
  }

  private static AskerReactivationRequestDTO reactivation(String password) {
    var dto = new AskerReactivationRequestDTO();
    dto.setUsername("asker");
    dto.setEmail("asker@example.org");
    dto.setTenantId(1L);
    dto.setPassword(password);
    return dto;
  }
}
