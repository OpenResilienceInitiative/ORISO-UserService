package de.caritas.cob.userservice.api.adapters.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CreateAnonymousEnquiryResponseDTOTest {

  private CreateAnonymousEnquiryResponseDTO givenAFullyPopulatedDTO() {
    return new CreateAnonymousEnquiryResponseDTO()
        .userName("User1008")
        .sessionId(153918L)
        .accessToken("eyJhbGciOiJSUzI1NiIs")
        .expiresIn(300)
        .refreshToken("eyJhbGciOiJSUzI1NiIsRefresh")
        .refreshExpiresIn(6000)
        .rcUserId("piYG2BAE9ng")
        .rcToken("hkmSBR_fHpjIx6amQ")
        .rcGroupId("7shdJkasdj3");
  }

  @Test
  void requiredArgsConstructor_Should_SetAllFields() {
    var dto =
        new CreateAnonymousEnquiryResponseDTO(
            "User1008",
            153918L,
            "accessToken",
            300,
            "refreshToken",
            6000,
            "rcUserId",
            "rcToken",
            "rcGroupId");

    assertThat(dto.getUserName()).isEqualTo("User1008");
    assertThat(dto.getSessionId()).isEqualTo(153918L);
    assertThat(dto.getAccessToken()).isEqualTo("accessToken");
    assertThat(dto.getExpiresIn()).isEqualTo(300);
    assertThat(dto.getRefreshToken()).isEqualTo("refreshToken");
    assertThat(dto.getRefreshExpiresIn()).isEqualTo(6000);
    assertThat(dto.getRcUserId()).isEqualTo("rcUserId");
    assertThat(dto.getRcToken()).isEqualTo("rcToken");
    assertThat(dto.getRcGroupId()).isEqualTo("rcGroupId");
  }

  @Test
  void builderChain_Should_RoundTripAllFields() {
    var dto = givenAFullyPopulatedDTO();

    assertThat(dto.getUserName()).isEqualTo("User1008");
    assertThat(dto.getSessionId()).isEqualTo(153918L);
    assertThat(dto.getAccessToken()).isEqualTo("eyJhbGciOiJSUzI1NiIs");
    assertThat(dto.getExpiresIn()).isEqualTo(300);
    assertThat(dto.getRefreshToken()).isEqualTo("eyJhbGciOiJSUzI1NiIsRefresh");
    assertThat(dto.getRefreshExpiresIn()).isEqualTo(6000);
    assertThat(dto.getRcUserId()).isEqualTo("piYG2BAE9ng");
    assertThat(dto.getRcToken()).isEqualTo("hkmSBR_fHpjIx6amQ");
    assertThat(dto.getRcGroupId()).isEqualTo("7shdJkasdj3");
  }

  @Test
  void setters_Should_RoundTripAllFields() {
    var dto = new CreateAnonymousEnquiryResponseDTO();
    dto.setUserName("User1008");
    dto.setSessionId(1L);
    dto.setAccessToken("token");
    dto.setExpiresIn(100);
    dto.setRefreshToken("refresh");
    dto.setRefreshExpiresIn(200);
    dto.setRcUserId("rcUser");
    dto.setRcToken("rcTok");
    dto.setRcGroupId("rcGroup");

    assertThat(dto.getUserName()).isEqualTo("User1008");
    assertThat(dto.getRcGroupId()).isEqualTo("rcGroup");
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

    assertThat(dto.equals("not a CreateAnonymousEnquiryResponseDTO")).isFalse();
  }

  @Test
  void toString_Should_ContainFieldValues() {
    var dto = givenAFullyPopulatedDTO();

    var result = dto.toString();

    assertThat(result).contains("User1008").contains("7shdJkasdj3");
  }

  @Test
  void equals_Should_ReturnFalse_When_AnyIndividualFieldDiffers() {
    var base = givenAFullyPopulatedDTO();

    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().userName("otherUser"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().sessionId(99L));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().accessToken("otherToken"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().expiresIn(99));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().refreshToken("otherRefresh"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().refreshExpiresIn(99));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().rcUserId("otherRcUserId"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().rcToken("otherRcToken"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().rcGroupId("otherRcGroupId"));
  }
}
