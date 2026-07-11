package de.caritas.cob.userservice.api.adapters.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SessionAdminDTOTest {

  private SessionAdminDTO givenAFullyPopulatedDTO() {
    return new SessionAdminDTO()
        .id(94L)
        .userId("1da238c6-cd46-4162-80f1-bff74eafe77f")
        .consultantId("consultantId")
        .username("username")
        .email("email@beratungcaritas.de")
        .consultingType(1)
        .postcode("12345")
        .agencyId(1)
        .isTeamSession(true)
        .messageDate("2026-01-01")
        .createDate("2026-01-02")
        .updateDate("2026-01-03");
  }

  @Test
  void builderChain_Should_RoundTripAllFields() {
    var dto = givenAFullyPopulatedDTO();

    assertThat(dto.getId()).isEqualTo(94L);
    assertThat(dto.getUserId()).isEqualTo("1da238c6-cd46-4162-80f1-bff74eafe77f");
    assertThat(dto.getConsultantId()).isEqualTo("consultantId");
    assertThat(dto.getUsername()).isEqualTo("username");
    assertThat(dto.getEmail()).isEqualTo("email@beratungcaritas.de");
    assertThat(dto.getConsultingType()).isEqualTo(1);
    assertThat(dto.getPostcode()).isEqualTo("12345");
    assertThat(dto.getAgencyId()).isEqualTo(1);
    assertThat(dto.getIsTeamSession()).isTrue();
    assertThat(dto.getMessageDate()).isEqualTo("2026-01-01");
    assertThat(dto.getCreateDate()).isEqualTo("2026-01-02");
    assertThat(dto.getUpdateDate()).isEqualTo("2026-01-03");
  }

  @Test
  void setters_Should_RoundTripAllFields() {
    var dto = new SessionAdminDTO();
    dto.setId(94L);
    dto.setUserId("userId");
    dto.setConsultantId("consultantId");
    dto.setUsername("username");
    dto.setEmail("email@beratungcaritas.de");
    dto.setConsultingType(1);
    dto.setPostcode("12345");
    dto.setAgencyId(1);
    dto.setIsTeamSession(true);
    dto.setMessageDate("2026-01-01");
    dto.setCreateDate("2026-01-02");
    dto.setUpdateDate("2026-01-03");

    assertThat(dto.getId()).isEqualTo(94L);
    assertThat(dto.getIsTeamSession()).isTrue();
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

    assertThat(dto.equals("not a SessionAdminDTO")).isFalse();
  }

  @Test
  void toString_Should_ContainFieldValues() {
    var dto = givenAFullyPopulatedDTO();

    var result = dto.toString();

    assertThat(result).contains("username").contains("email@beratungcaritas.de");
  }

  @Test
  void equals_Should_ReturnFalse_When_AnyIndividualFieldDiffers() {
    var base = givenAFullyPopulatedDTO();

    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().id(99L));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().userId("otherUserId"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().consultantId("otherConsultantId"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().username("otherUsername"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().email("other@example.com"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().consultingType(99));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().postcode("00000"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().agencyId(99));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().isTeamSession(false));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().messageDate("2027-01-01"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().createDate("2027-01-02"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().updateDate("2027-01-03"));
  }
}
