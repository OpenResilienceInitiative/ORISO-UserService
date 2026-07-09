package de.caritas.cob.userservice.api.adapters.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TwoFactorAuthDTOTest {

  private TwoFactorAuthDTO givenAFullyPopulatedDTO() {
    return new TwoFactorAuthDTO()
        .isEnabled(true)
        .isActive(true)
        .secret("secret")
        .qrCode("qrCode")
        .type(OtpType.APP)
        .isToEncourage(false);
  }

  @Test
  void requiredArgsConstructor_Should_SetRequiredFields() {
    var dto = new TwoFactorAuthDTO(true, false);

    assertThat(dto.getIsEnabled()).isTrue();
    assertThat(dto.getIsActive()).isFalse();
  }

  @Test
  void defaultConstructor_Should_ApplyDefaultValues() {
    var dto = new TwoFactorAuthDTO();

    assertThat(dto.getIsEnabled()).isFalse();
    assertThat(dto.getIsActive()).isFalse();
    assertThat(dto.getIsToEncourage()).isTrue();
    assertThat(dto.getSecret()).isNull();
    assertThat(dto.getQrCode()).isNull();
    assertThat(dto.getType()).isNull();
  }

  @Test
  void builderChain_Should_RoundTripAllFields() {
    var dto = givenAFullyPopulatedDTO();

    assertThat(dto.getIsEnabled()).isTrue();
    assertThat(dto.getIsActive()).isTrue();
    assertThat(dto.getSecret()).isEqualTo("secret");
    assertThat(dto.getQrCode()).isEqualTo("qrCode");
    assertThat(dto.getType()).isEqualTo(OtpType.APP);
    assertThat(dto.getIsToEncourage()).isFalse();
  }

  @Test
  void setters_Should_RoundTripAllFields() {
    var dto = new TwoFactorAuthDTO();
    dto.setIsEnabled(true);
    dto.setIsActive(false);
    dto.setSecret("s3cr3t");
    dto.setQrCode("qr");
    dto.setType(OtpType.EMAIL);
    dto.setIsToEncourage(false);

    assertThat(dto.getIsEnabled()).isTrue();
    assertThat(dto.getType()).isEqualTo(OtpType.EMAIL);
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

    assertThat(dto.equals("not a TwoFactorAuthDTO")).isFalse();
  }

  @Test
  void toString_Should_ContainFieldValues() {
    var dto = givenAFullyPopulatedDTO();

    var result = dto.toString();

    assertThat(result).contains("secret").contains("qrCode");
  }

  @Test
  void equals_Should_ReturnFalse_When_AnyIndividualFieldDiffers() {
    var base = givenAFullyPopulatedDTO();

    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().isEnabled(false));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().isActive(false));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().secret("otherSecret"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().qrCode("otherQrCode"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().type(OtpType.EMAIL));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().isToEncourage(true));
  }
}
