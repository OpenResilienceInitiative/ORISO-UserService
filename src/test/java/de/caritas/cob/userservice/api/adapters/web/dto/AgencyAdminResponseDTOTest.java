package de.caritas.cob.userservice.api.adapters.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgencyAdminResponseDTOTest {

  private AgencyAdminResponseDTO givenAFullyPopulatedDTO() {
    return new AgencyAdminResponseDTO()
        .id(1L)
        .dioceseId(2L)
        .name("Suchtberatung Freiburg")
        .description("description")
        .postcode("79106")
        .city("Bonn")
        .teamAgency(true)
        .offline(false)
        .consultingType(1)
        .url("https://www.domain.com")
        .external(true)
        .createDate("2026-01-01")
        .updateDate("2026-01-02")
        .deleteDate(null);
  }

  @Test
  void builderChain_Should_RoundTripAllFields() {
    var dto = givenAFullyPopulatedDTO();

    assertThat(dto.getId()).isEqualTo(1L);
    assertThat(dto.getDioceseId()).isEqualTo(2L);
    assertThat(dto.getName()).isEqualTo("Suchtberatung Freiburg");
    assertThat(dto.getDescription()).isEqualTo("description");
    assertThat(dto.getPostcode()).isEqualTo("79106");
    assertThat(dto.getCity()).isEqualTo("Bonn");
    assertThat(dto.getTeamAgency()).isTrue();
    assertThat(dto.getOffline()).isFalse();
    assertThat(dto.getConsultingType()).isEqualTo(1);
    assertThat(dto.getUrl()).isEqualTo("https://www.domain.com");
    assertThat(dto.getExternal()).isTrue();
    assertThat(dto.getCreateDate()).isEqualTo("2026-01-01");
    assertThat(dto.getUpdateDate()).isEqualTo("2026-01-02");
    assertThat(dto.getDeleteDate()).isNull();
  }

  @Test
  void setters_Should_RoundTripAllFields() {
    var dto = new AgencyAdminResponseDTO();
    dto.setId(1L);
    dto.setDioceseId(2L);
    dto.setName("name");
    dto.setDescription("description");
    dto.setPostcode("79106");
    dto.setCity("Bonn");
    dto.setTeamAgency(true);
    dto.setOffline(false);
    dto.setConsultingType(1);
    dto.setUrl("https://www.domain.com");
    dto.setExternal(true);
    dto.setCreateDate("2026-01-01");
    dto.setUpdateDate("2026-01-02");
    dto.setDeleteDate("2026-01-03");

    assertThat(dto.getId()).isEqualTo(1L);
    assertThat(dto.getDeleteDate()).isEqualTo("2026-01-03");
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

    assertThat(dto.equals("not a AgencyAdminResponseDTO")).isFalse();
  }

  @Test
  void toString_Should_ContainFieldValues() {
    var dto = givenAFullyPopulatedDTO();

    var result = dto.toString();

    assertThat(result).contains("Suchtberatung Freiburg").contains("Bonn");
  }

  @Test
  void equals_Should_ReturnFalse_When_AnyIndividualFieldDiffers() {
    var base = givenAFullyPopulatedDTO();

    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().id(99L));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().dioceseId(99L));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().name("otherName"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().description("otherDescription"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().postcode("00000"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().city("otherCity"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().teamAgency(false));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().offline(true));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().consultingType(99));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().url("https://other.example.com"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().external(false));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().createDate("2027-01-01"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().updateDate("2027-01-02"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().deleteDate("2027-01-03"));
  }
}
