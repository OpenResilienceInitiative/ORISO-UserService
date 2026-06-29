package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PrivacyEnvelopeTest {

  @Test
  void builder_Should_SetAllFields() {
    PrivacyEnvelope envelope =
        PrivacyEnvelope.builder()
            .messageId("msg-1")
            .roomId("!room:matrix.org")
            .senderId("@sender:matrix.org")
            .timestamp(1234567890L)
            .hasAttachment(true)
            .contentClass("IMAGE")
            .build();

    assertThat(envelope.getMessageId()).isEqualTo("msg-1");
    assertThat(envelope.getRoomId()).isEqualTo("!room:matrix.org");
    assertThat(envelope.getSenderId()).isEqualTo("@sender:matrix.org");
    assertThat(envelope.getTimestamp()).isEqualTo(1234567890L);
    assertThat(envelope.isHasAttachment()).isTrue();
    assertThat(envelope.getContentClass()).isEqualTo("IMAGE");
  }

  @Test
  void builder_Should_AllowNullOptionalFields() {
    PrivacyEnvelope envelope =
        PrivacyEnvelope.builder().messageId("msg-2").roomId("!room:matrix.org").build();

    assertThat(envelope.getMessageId()).isEqualTo("msg-2");
    assertThat(envelope.getSenderId()).isNull();
    assertThat(envelope.getTimestamp()).isNull();
    assertThat(envelope.isHasAttachment()).isFalse();
    assertThat(envelope.getContentClass()).isNull();
  }

  @Test
  void equalsAndHashCode_Should_WorkCorrectly() {
    PrivacyEnvelope a =
        PrivacyEnvelope.builder().messageId("x").roomId("!r:m").hasAttachment(false).build();
    PrivacyEnvelope b =
        PrivacyEnvelope.builder().messageId("x").roomId("!r:m").hasAttachment(false).build();

    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  @Test
  void toString_Should_ContainFields() {
    PrivacyEnvelope envelope =
        PrivacyEnvelope.builder().messageId("msg-99").contentClass("FILE").build();

    assertThat(envelope.toString()).contains("msg-99").contains("FILE");
  }
}
