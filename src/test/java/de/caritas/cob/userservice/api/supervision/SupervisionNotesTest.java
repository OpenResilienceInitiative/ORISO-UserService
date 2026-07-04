package de.caritas.cob.userservice.api.supervision;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** ADR-008 item 4 (DRAFT): {@link SupervisionNotes} encode/decode round trip + legacy fallback. */
class SupervisionNotesTest {

  @Test
  void encodeThenDecode_Should_roundTrip_reasonJustificationAndConsent() {
    String encoded =
        SupervisionNotes.encode(
            SupervisionReason.SAFEGUARDING_U25, "minor at risk", SupervisionConsent.PENDING);

    assertThat(encoded).startsWith("{");

    SupervisionNotes.Payload decoded = SupervisionNotes.decode(encoded);
    assertThat(decoded.reasonCode).isEqualTo("SAFEGUARDING_U25");
    assertThat(decoded.justification).isEqualTo("minor at risk");
    assertThat(decoded.consent).isEqualTo(SupervisionConsent.PENDING.name());
  }

  @Test
  void decode_Should_treatLegacyFreeText_asJustification_withNoReason_andNotRequiredConsent() {
    SupervisionNotes.Payload decoded = SupervisionNotes.decode("just some old free text note");

    assertThat(decoded.reasonCode).isNull();
    assertThat(decoded.justification).isEqualTo("just some old free text note");
    assertThat(decoded.consent).isEqualTo(SupervisionConsent.NOT_REQUIRED.name());
  }

  @Test
  void decode_Should_handleNullAndBlank_asEmptyNotRequiredPayload() {
    SupervisionNotes.Payload fromNull = SupervisionNotes.decode(null);
    assertThat(fromNull.reasonCode).isNull();
    assertThat(fromNull.justification).isNull();
    assertThat(fromNull.consent).isEqualTo(SupervisionConsent.NOT_REQUIRED.name());

    SupervisionNotes.Payload fromBlank = SupervisionNotes.decode("   ");
    assertThat(fromBlank.consent).isEqualTo(SupervisionConsent.NOT_REQUIRED.name());
  }

  @Test
  void encode_Should_serializeNullReason_onLegacyPath() {
    String encoded =
        SupervisionNotes.encode(null, "legacy justification", SupervisionConsent.NOT_REQUIRED);

    SupervisionNotes.Payload decoded = SupervisionNotes.decode(encoded);
    assertThat(decoded.reasonCode).isNull();
    assertThat(decoded.justification).isEqualTo("legacy justification");
    assertThat(decoded.consent).isEqualTo(SupervisionConsent.NOT_REQUIRED.name());
  }
}
