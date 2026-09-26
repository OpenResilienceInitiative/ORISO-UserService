package de.caritas.cob.userservice.api.adapters.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GlobalSmtpTestEmailDTOTest {

  private GlobalSmtpTestEmailDTO fullyPopulated() {
    GlobalSmtpTestEmailDTO dto = new GlobalSmtpTestEmailDTO();
    dto.setRecipientEmail("recipient@example.com");
    dto.setEmailThemeColor("#ff0000");
    return dto;
  }

  @Test
  void gettersAndSetters_Should_roundTrip_allFields() {
    GlobalSmtpTestEmailDTO dto = fullyPopulated();

    assertThat(dto.getRecipientEmail()).isEqualTo("recipient@example.com");
    assertThat(dto.getEmailThemeColor()).isEqualTo("#ff0000");
  }

  @Test
  void noArgsConstructor_Should_leaveFieldsNull() {
    GlobalSmtpTestEmailDTO dto = new GlobalSmtpTestEmailDTO();

    assertThat(dto.getRecipientEmail()).isNull();
    assertThat(dto.getEmailThemeColor()).isNull();
  }

  @Test
  void equalsAndHashCode_Should_beEqual_When_allFieldsMatch() {
    assertThat(fullyPopulated()).isEqualTo(fullyPopulated());
    assertThat(fullyPopulated().hashCode()).isEqualTo(fullyPopulated().hashCode());
  }

  @Test
  void equals_Should_returnFalse_When_recipientEmailDiffers() {
    GlobalSmtpTestEmailDTO a = fullyPopulated();
    GlobalSmtpTestEmailDTO b = fullyPopulated();
    b.setRecipientEmail("different@example.com");
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equals_Should_returnFalse_When_emailThemeColorDiffers() {
    GlobalSmtpTestEmailDTO a = fullyPopulated();
    GlobalSmtpTestEmailDTO b = fullyPopulated();
    b.setEmailThemeColor("#00ff00");
    assertThat(a).isNotEqualTo(b);
  }
}
