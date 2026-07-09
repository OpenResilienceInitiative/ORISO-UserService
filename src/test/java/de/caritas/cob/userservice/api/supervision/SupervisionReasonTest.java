package de.caritas.cob.userservice.api.supervision;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** ADR-008 item 4 (DRAFT): reason lookup + consent-required flags + {@code initialFor} mapping. */
class SupervisionReasonTest {

  @Test
  void fromCode_Should_resolveKnownCode_caseInsensitively_andTrim() {
    assertThat(SupervisionReason.fromCode("peer_support")).contains(SupervisionReason.PEER_SUPPORT);
    assertThat(SupervisionReason.fromCode("  SAFEGUARDING_U25 "))
        .contains(SupervisionReason.SAFEGUARDING_U25);
  }

  @Test
  void fromCode_Should_beEmpty_forNullOrUnknownCode() {
    assertThat(SupervisionReason.fromCode(null)).isEmpty();
    assertThat(SupervisionReason.fromCode("NOPE")).isEmpty();
  }

  @Test
  void clinicallySensitiveReasons_Should_requireConsent_othersShouldNot() {
    assertThat(SupervisionReason.CLINICAL_OVERSIGHT.isClientConsentRequired()).isTrue();
    assertThat(SupervisionReason.SAFEGUARDING_U25.isClientConsentRequired()).isTrue();
    assertThat(SupervisionReason.PEER_SUPPORT.isClientConsentRequired()).isFalse();
    assertThat(SupervisionReason.TRAINING.isClientConsentRequired()).isFalse();
  }

  @Test
  void initialFor_Should_bePending_whenConsentRequired_elseNotRequired() {
    assertThat(SupervisionConsent.initialFor(SupervisionReason.CLINICAL_OVERSIGHT))
        .isEqualTo(SupervisionConsent.PENDING);
    assertThat(SupervisionConsent.initialFor(SupervisionReason.SAFEGUARDING_U25))
        .isEqualTo(SupervisionConsent.PENDING);
    assertThat(SupervisionConsent.initialFor(SupervisionReason.PEER_SUPPORT))
        .isEqualTo(SupervisionConsent.NOT_REQUIRED);
    assertThat(SupervisionConsent.initialFor(SupervisionReason.TRAINING))
        .isEqualTo(SupervisionConsent.NOT_REQUIRED);
  }

  @Test
  void initialFor_Should_beNotRequired_forNullReason_legacyPath() {
    assertThat(SupervisionConsent.initialFor(null)).isEqualTo(SupervisionConsent.NOT_REQUIRED);
  }
}
