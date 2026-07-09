package de.caritas.cob.userservice.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentials;
import org.junit.jupiter.api.Test;

class AppintmentEnquiryDataTest {

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

  private AppintmentEnquiryData givenAFullyPopulated() {
    var data =
        new AppintmentEnquiryData(givenAUser(), 1L, "message", "de", givenRocketChatCredentials());
    data.setType("APPOINTMENT");
    return data;
  }

  @Test
  void allArgsConstructor_Should_SetAllFinalFields() {
    var user = givenAUser();
    var rcCredentials = givenRocketChatCredentials();

    var data = new AppintmentEnquiryData(user, 1L, "message", "de", rcCredentials);

    assertThat(data.getUser()).isEqualTo(user);
    assertThat(data.getSessionId()).isEqualTo(1L);
    assertThat(data.getMessage()).isEqualTo("message");
    assertThat(data.getLanguage()).isEqualTo("de");
    assertThat(data.getRocketChatCredentials()).isEqualTo(rcCredentials);
    assertThat(data.getType()).isNull();
  }

  @Test
  void setType_Should_UpdateMutableField() {
    var data =
        new AppintmentEnquiryData(givenAUser(), 1L, "message", "de", givenRocketChatCredentials());

    data.setType("APPOINTMENT");

    assertThat(data.getType()).isEqualTo("APPOINTMENT");
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

    assertThat(data.equals("not an AppintmentEnquiryData")).isFalse();
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
    var other =
        new AppintmentEnquiryData(givenAUser(), 2L, "message", "de", givenRocketChatCredentials());
    other.setType("APPOINTMENT");

    assertThat(base).isNotEqualTo(other);
  }

  @Test
  void toString_Should_ContainFieldValues() {
    var data = givenAFullyPopulated();

    assertThat(data.toString()).contains("message").contains("APPOINTMENT");
  }
}
