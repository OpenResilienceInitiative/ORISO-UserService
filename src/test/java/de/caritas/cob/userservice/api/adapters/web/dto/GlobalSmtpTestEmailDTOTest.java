package de.caritas.cob.userservice.api.adapters.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GlobalSmtpTestEmailDTOTest {

  private GlobalSmtpTestEmailDTO fullyPopulated() {
    GlobalSmtpTestEmailDTO dto = new GlobalSmtpTestEmailDTO();
    dto.setHost("smtp.example.com");
    dto.setPort(587);
    dto.setSecure(true);
    dto.setFrom("sender@example.com");
    dto.setRecipientEmail("recipient@example.com");
    dto.setEmailThemeColor("#ff0000");
    return dto;
  }

  @Test
  void gettersAndSetters_Should_roundTrip_allFields() {
    GlobalSmtpTestEmailDTO dto = fullyPopulated();

    assertThat(dto.getHost()).isEqualTo("smtp.example.com");
    assertThat(dto.getPort()).isEqualTo(587);
    assertThat(dto.getSecure()).isTrue();
    assertThat(dto.getFrom()).isEqualTo("sender@example.com");
    assertThat(dto.getRecipientEmail()).isEqualTo("recipient@example.com");
    assertThat(dto.getEmailThemeColor()).isEqualTo("#ff0000");
  }

  @Test
  void noArgsConstructor_Should_leaveFieldsNull() {
    GlobalSmtpTestEmailDTO dto = new GlobalSmtpTestEmailDTO();

    assertThat(dto.getHost()).isNull();
    assertThat(dto.getPort()).isNull();
    assertThat(dto.getSecure()).isNull();
    assertThat(dto.getFrom()).isNull();
    assertThat(dto.getRecipientEmail()).isNull();
    assertThat(dto.getEmailThemeColor()).isNull();
  }

  @Test
  void equalsAndHashCode_Should_beEqual_When_allFieldsMatch() {
    assertThat(fullyPopulated()).isEqualTo(fullyPopulated());
    assertThat(fullyPopulated().hashCode()).isEqualTo(fullyPopulated().hashCode());
  }

  @Test
  void equalsAndHashCode_Should_beEqual_When_sameInstance() {
    GlobalSmtpTestEmailDTO dto = fullyPopulated();
    assertThat(dto).isEqualTo(dto);
  }

  @Test
  void equals_Should_returnFalse_When_comparedToNullOrDifferentType() {
    GlobalSmtpTestEmailDTO dto = fullyPopulated();
    assertThat(dto).isNotEqualTo(null);
    assertThat(dto).isNotEqualTo("not a dto");
  }

  @Test
  void equals_Should_returnFalse_When_hostDiffers() {
    GlobalSmtpTestEmailDTO a = fullyPopulated();
    GlobalSmtpTestEmailDTO b = fullyPopulated();
    b.setHost("other.example.com");
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equals_Should_returnFalse_When_portDiffers() {
    GlobalSmtpTestEmailDTO a = fullyPopulated();
    GlobalSmtpTestEmailDTO b = fullyPopulated();
    b.setPort(25);
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equals_Should_returnFalse_When_secureDiffers() {
    GlobalSmtpTestEmailDTO a = fullyPopulated();
    GlobalSmtpTestEmailDTO b = fullyPopulated();
    b.setSecure(false);
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equals_Should_returnFalse_When_fromDiffers() {
    GlobalSmtpTestEmailDTO a = fullyPopulated();
    GlobalSmtpTestEmailDTO b = fullyPopulated();
    b.setFrom("different@example.com");
    assertThat(a).isNotEqualTo(b);
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

  @Test
  void toString_Should_containAllFieldValues() {
    String result = fullyPopulated().toString();

    assertThat(result)
        .contains("smtp.example.com")
        .contains("587")
        .contains("true")
        .contains("sender@example.com")
        .contains("recipient@example.com")
        .contains("#ff0000");
  }
}
