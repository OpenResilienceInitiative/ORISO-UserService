package de.caritas.cob.userservice.api.adapters.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class PatchUserDTOTest {

  private PatchUserDTO givenAFullyPopulatedDTO() {
    return new PatchUserDTO()
        .encourage2fa(true)
        .magicLinkLoginEnabled(false)
        .displayName("Display Name")
        .walkThroughEnabled(true)
        .emailToggles(Set.of(new EmailToggle(EmailType.DAILY_ENQUIRY, true)))
        .preferredLanguage(LanguageCode.DE)
        .termsAndConditionsConfirmation(true)
        .dataPrivacyConfirmation(true)
        .available(false)
        .emailNotifications(new EmailNotificationsDTO(true));
  }

  @Test
  void builderChain_Should_RoundTripAllFields() {
    var dto = givenAFullyPopulatedDTO();

    assertThat(dto.getEncourage2fa()).isTrue();
    assertThat(dto.getMagicLinkLoginEnabled()).isFalse();
    assertThat(dto.getDisplayName()).isEqualTo("Display Name");
    assertThat(dto.getWalkThroughEnabled()).isTrue();
    assertThat(dto.getEmailToggles()).hasSize(1);
    assertThat(dto.getPreferredLanguage()).isEqualTo(LanguageCode.DE);
    assertThat(dto.getTermsAndConditionsConfirmation()).isTrue();
    assertThat(dto.getDataPrivacyConfirmation()).isTrue();
    assertThat(dto.getAvailable()).isFalse();
    assertThat(dto.getEmailNotifications().getEmailNotificationsEnabled()).isTrue();
  }

  @Test
  void setters_Should_RoundTripAllFields() {
    var dto = new PatchUserDTO();
    dto.setEncourage2fa(true);
    dto.setMagicLinkLoginEnabled(false);
    dto.setDisplayName("Display Name");
    dto.setWalkThroughEnabled(true);
    dto.setEmailToggles(Set.of(new EmailToggle(EmailType.DAILY_ENQUIRY, true)));
    dto.setPreferredLanguage(LanguageCode.DE);
    dto.setTermsAndConditionsConfirmation(true);
    dto.setDataPrivacyConfirmation(true);
    dto.setAvailable(false);
    dto.setEmailNotifications(new EmailNotificationsDTO(true));

    assertThat(dto.getDisplayName()).isEqualTo("Display Name");
    assertThat(dto.getEmailToggles()).hasSize(1);
  }

  @Test
  void addEmailTogglesItem_Should_InitializeSet_When_EmailTogglesIsNull() {
    var dto = new PatchUserDTO();
    dto.setEmailToggles(null);

    dto.addEmailTogglesItem(new EmailToggle(EmailType.DAILY_ENQUIRY, true));

    assertThat(dto.getEmailToggles()).hasSize(1);
  }

  @Test
  void addEmailTogglesItem_Should_AppendToExistingSet_When_EmailTogglesAlreadyInitialized() {
    var dto = new PatchUserDTO();

    dto.addEmailTogglesItem(new EmailToggle(EmailType.DAILY_ENQUIRY, true));
    dto.addEmailTogglesItem(new EmailToggle(EmailType.NEW_CHAT_MESSAGE_FROM_ADVICE_SEEKER, false));

    assertThat(dto.getEmailToggles()).hasSize(2);
  }

  @Test
  void equals_Should_ReturnTrue_When_SameInstance() {
    var dto = givenAFullyPopulatedDTO();

    assertThat(dto.equals(dto)).isTrue();
  }

  @Test
  void equals_Should_ReturnTrue_When_AllFieldsMatch() {
    var dto1 = givenAFullyPopulatedDTO();
    var dto2 = givenAFullyPopulatedDTO();

    assertThat(dto1).isEqualTo(dto2);
    assertThat(dto1.hashCode()).isEqualTo(dto2.hashCode());
  }

  @Test
  void equals_Should_ReturnFalse_When_ComparedToNull() {
    var dto = givenAFullyPopulatedDTO();

    assertThat(dto.equals(null)).isFalse();
  }

  @Test
  void equals_Should_ReturnFalse_When_ComparedToDifferentClass() {
    var dto = givenAFullyPopulatedDTO();

    assertThat(dto.equals("not a PatchUserDTO")).isFalse();
  }

  @Test
  void toString_Should_ContainFieldValues() {
    var dto = givenAFullyPopulatedDTO();

    var result = dto.toString();

    assertThat(result).contains("Display Name");
  }

  @Test
  void equals_Should_ReturnFalse_When_AnyIndividualFieldDiffers() {
    var base = givenAFullyPopulatedDTO();

    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().encourage2fa(false));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().magicLinkLoginEnabled(true));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().displayName("Other Display"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().walkThroughEnabled(false));
    assertThat(base)
        .isNotEqualTo(
            givenAFullyPopulatedDTO()
                .emailToggles(
                    Set.of(new EmailToggle(EmailType.NEW_CHAT_MESSAGE_FROM_ADVICE_SEEKER, true))));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().preferredLanguage(LanguageCode.EN));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().termsAndConditionsConfirmation(false));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().dataPrivacyConfirmation(false));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().available(true));
    assertThat(base)
        .isNotEqualTo(
            givenAFullyPopulatedDTO().emailNotifications(new EmailNotificationsDTO(false)));
  }
}
