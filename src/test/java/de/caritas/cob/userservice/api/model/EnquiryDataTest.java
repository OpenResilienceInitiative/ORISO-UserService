package de.caritas.cob.userservice.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentials;
import org.junit.jupiter.api.Test;

class EnquiryDataTest {

  private User givenAUser() {
    return User.builder().userId("user-1").username("username").email("user@example.com").build();
  }

  private RocketChatCredentials givenRocketChatCredentials() {
    return RocketChatCredentials.builder()
        .rocketChatToken("token")
        .rocketChatUserId("rcUserId")
        .rocketChatUsername("rcUsername")
        .build();
  }

  private EnquiryData givenAFullyPopulated() {
    var data = new EnquiryData(givenAUser(), 1L, "message", "de", givenRocketChatCredentials());
    data.setType("ENQUIRY");
    data.setConsultantEmail("consultant@example.com");
    return data;
  }

  @Test
  void allArgsConstructor_Should_SetAllFinalFields() {
    var user = givenAUser();
    var rcCredentials = givenRocketChatCredentials();

    var data = new EnquiryData(user, 1L, "message", "de", rcCredentials);

    assertThat(data.getUser()).isEqualTo(user);
    assertThat(data.getSessionId()).isEqualTo(1L);
    assertThat(data.getMessage()).isEqualTo("message");
    assertThat(data.getLanguage()).isEqualTo("de");
    assertThat(data.getRocketChatCredentials()).isEqualTo(rcCredentials);
    assertThat(data.getType()).isNull();
    assertThat(data.getConsultantEmail()).isNull();
  }

  @Test
  void setType_And_SetConsultantEmail_Should_UpdateMutableFields() {
    var data = new EnquiryData(givenAUser(), 1L, "message", "de", givenRocketChatCredentials());

    data.setType("ENQUIRY");
    data.setConsultantEmail("consultant@example.com");

    assertThat(data.getType()).isEqualTo("ENQUIRY");
    assertThat(data.getConsultantEmail()).isEqualTo("consultant@example.com");
  }

  @Test
  void equals_Should_ReturnTrue_When_SameInstance() {
    var data = givenAFullyPopulated();

    assertThat(data.equals(data)).isTrue();
  }

  @Test
  void equals_Should_ReturnTrue_When_AllFieldsMatch() {
    var data1 = givenAFullyPopulated();
    var data2 = givenAFullyPopulated();

    assertThat(data1).isEqualTo(data2);
    assertThat(data1.hashCode()).isEqualTo(data2.hashCode());
  }

  @Test
  void equals_Should_ReturnFalse_When_ComparedToNull() {
    var data = givenAFullyPopulated();

    assertThat(data.equals(null)).isFalse();
  }

  @Test
  void equals_Should_ReturnFalse_When_ComparedToDifferentClass() {
    var data = givenAFullyPopulated();

    assertThat(data.equals("not an EnquiryData")).isFalse();
  }

  @Test
  void equals_Should_ReturnFalse_When_ConsultantEmailDiffers() {
    var base = givenAFullyPopulated();
    var other = givenAFullyPopulated();
    other.setConsultantEmail("other@example.com");

    assertThat(base).isNotEqualTo(other);
  }

  @Test
  void equals_Should_ReturnFalse_When_TypeDiffers() {
    var base = givenAFullyPopulated();
    var other = givenAFullyPopulated();
    other.setType("OTHER");

    assertThat(base).isNotEqualTo(other);
  }

  @Test
  void equals_Should_ReturnFalse_When_SessionIdDiffers() {
    var base = givenAFullyPopulated();
    var other = new EnquiryData(givenAUser(), 2L, "message", "de", givenRocketChatCredentials());
    other.setType("ENQUIRY");
    other.setConsultantEmail("consultant@example.com");

    assertThat(base).isNotEqualTo(other);
  }

  @Test
  void toString_Should_ContainFieldValues() {
    var data = givenAFullyPopulated();

    assertThat(data.toString()).contains("message").contains("consultant@example.com");
  }
}
