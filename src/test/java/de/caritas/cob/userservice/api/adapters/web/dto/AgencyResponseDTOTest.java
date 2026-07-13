package de.caritas.cob.userservice.api.adapters.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgencyResponseDTOTest {

  private AgencyResponseDTO givenAFullyPopulatedDTO() {
    return new AgencyResponseDTO()
        .id(1L)
        .name("Suchtberatung Freiburg")
        .postcode("79106")
        .city("Bonn")
        .description("description")
        .teamAgency(true)
        .offline(false)
        .consultingType(1)
        .tenantId(12L)
        .topicIds(List.of(1L, 2L));
  }

  @Test
  void builderChain_Should_RoundTripAllFields() {
    var dto = givenAFullyPopulatedDTO();

    assertThat(dto.getId()).isEqualTo(1L);
    assertThat(dto.getName()).isEqualTo("Suchtberatung Freiburg");
    assertThat(dto.getPostcode()).isEqualTo("79106");
    assertThat(dto.getCity()).isEqualTo("Bonn");
    assertThat(dto.getDescription()).isEqualTo("description");
    assertThat(dto.getTeamAgency()).isTrue();
    assertThat(dto.getOffline()).isFalse();
    assertThat(dto.getConsultingType()).isEqualTo(1);
    assertThat(dto.getTenantId()).isEqualTo(12L);
    assertThat(dto.getTopicIds()).containsExactly(1L, 2L);
  }

  @Test
  void setters_Should_RoundTripAllFields() {
    var dto = new AgencyResponseDTO();
    dto.setId(1L);
    dto.setName("name");
    dto.setPostcode("79106");
    dto.setCity("Bonn");
    dto.setDescription("description");
    dto.setTeamAgency(true);
    dto.setOffline(false);
    dto.setConsultingType(1);
    dto.setTenantId(12L);
    dto.setTopicIds(List.of(1L));

    assertThat(dto.getId()).isEqualTo(1L);
    assertThat(dto.getTopicIds()).hasSize(1);
  }

  @Test
  void addTopicIdsItem_Should_InitializeList_When_TopicIdsIsNull() {
    var dto = new AgencyResponseDTO();
    dto.setTopicIds(null);

    dto.addTopicIdsItem(1L);

    assertThat(dto.getTopicIds()).containsExactly(1L);
  }

  @Test
  void addTopicIdsItem_Should_AppendToExistingList_When_TopicIdsAlreadyInitialized() {
    var dto = new AgencyResponseDTO();

    dto.addTopicIdsItem(1L);
    dto.addTopicIdsItem(2L);

    assertThat(dto.getTopicIds()).containsExactly(1L, 2L);
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

    assertThat(dto.equals("not a AgencyResponseDTO")).isFalse();
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
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().name("otherName"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().postcode("00000"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().city("otherCity"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().description("otherDescription"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().teamAgency(false));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().offline(true));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().consultingType(99));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().tenantId(99L));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().topicIds(List.of(99L)));
  }
}
