package de.caritas.cob.userservice.api.adapters.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class UpdateConsultantDTOTest {

  private UpdateConsultantDTO givenAFullyPopulatedDTO() {
    return new UpdateConsultantDTO()
        .firstname("Max")
        .lastname("Mustermann")
        .email("maxmuster@mann.com")
        .languages(List.of(LanguageCode.DE, LanguageCode.EN))
        .termsAndConditionsConfirmation(true)
        .dataPrivacyConfirmation(true)
        .emailNotifications(new EmailNotificationsDTO(true));
  }

  @Test
  void requiredArgsConstructor_Should_SetRequiredFields() {
    var dto = new UpdateConsultantDTO("Max", "Mustermann", "maxmuster@mann.com");

    assertThat(dto.getFirstname()).isEqualTo("Max");
    assertThat(dto.getLastname()).isEqualTo("Mustermann");
    assertThat(dto.getEmail()).isEqualTo("maxmuster@mann.com");
  }

  @Test
  void builderChain_Should_RoundTripAllFields() {
    var dto = givenAFullyPopulatedDTO();

    assertThat(dto.getFirstname()).isEqualTo("Max");
    assertThat(dto.getLastname()).isEqualTo("Mustermann");
    assertThat(dto.getEmail()).isEqualTo("maxmuster@mann.com");
    assertThat(dto.getLanguages()).containsExactly(LanguageCode.DE, LanguageCode.EN);
    assertThat(dto.getTermsAndConditionsConfirmation()).isTrue();
    assertThat(dto.getDataPrivacyConfirmation()).isTrue();
    assertThat(dto.getEmailNotifications().getEmailNotificationsEnabled()).isTrue();
  }

  @Test
  void setters_Should_RoundTripAllFields() {
    var dto = new UpdateConsultantDTO();
    dto.setFirstname("First");
    dto.setLastname("Last");
    dto.setEmail("mail@example.com");
    dto.setLanguages(List.of(LanguageCode.FR));
    dto.setTermsAndConditionsConfirmation(false);
    dto.setDataPrivacyConfirmation(false);
    dto.setEmailNotifications(new EmailNotificationsDTO(false));

    assertThat(dto.getFirstname()).isEqualTo("First");
    assertThat(dto.getLanguages()).containsExactly(LanguageCode.FR);
  }

  @Test
  void addLanguagesItem_Should_InitializeList_When_LanguagesIsNull() {
    var dto = new UpdateConsultantDTO();
    dto.setLanguages(null);

    dto.addLanguagesItem(LanguageCode.DE);

    assertThat(dto.getLanguages()).containsExactly(LanguageCode.DE);
  }

  @Test
  void addLanguagesItem_Should_AppendToExistingList_When_LanguagesAlreadyInitialized() {
    var dto = new UpdateConsultantDTO();

    dto.addLanguagesItem(LanguageCode.DE);
    dto.addLanguagesItem(LanguageCode.EN);

    assertThat(dto.getLanguages()).containsExactly(LanguageCode.DE, LanguageCode.EN);
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

    assertThat(dto.equals("not an UpdateConsultantDTO")).isFalse();
  }

  @Test
  void toString_Should_ContainFieldValues() {
    var dto = givenAFullyPopulatedDTO();

    var result = dto.toString();

    assertThat(result).contains("Mustermann").contains("maxmuster@mann.com");
  }

  @Test
  void equals_Should_ReturnFalse_When_AnyIndividualFieldDiffers() {
    var base = givenAFullyPopulatedDTO();

    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().firstname("otherFirstname"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().lastname("otherLastname"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().email("other@example.com"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().languages(List.of(LanguageCode.FR)));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().termsAndConditionsConfirmation(false));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().dataPrivacyConfirmation(false));
    assertThat(base)
        .isNotEqualTo(
            givenAFullyPopulatedDTO().emailNotifications(new EmailNotificationsDTO(false)));
  }
}
