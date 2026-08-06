package de.caritas.cob.userservice.api.adapters.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConsultantResponseDTOTest {

  private ConsultantResponseDTO givenAFullyPopulatedDTO() {
    return new ConsultantResponseDTO()
        .consultantId("aadc0ecf-c048-4bfc-857d-8c9b2e425500")
        .firstName("Max")
        .lastName("Mustermann")
        .displayName("Max M.")
        .username("max.mustermann")
        .isSupervisor(true)
        .agencies(List.of(new AgencyResponseDTO().id(1L)));
  }

  @Test
  void builderChain_Should_RoundTripAllFields() {
    var dto = givenAFullyPopulatedDTO();

    assertThat(dto.getConsultantId()).isEqualTo("aadc0ecf-c048-4bfc-857d-8c9b2e425500");
    assertThat(dto.getFirstName()).isEqualTo("Max");
    assertThat(dto.getLastName()).isEqualTo("Mustermann");
    assertThat(dto.getDisplayName()).isEqualTo("Max M.");
    assertThat(dto.getUsername()).isEqualTo("max.mustermann");
    assertThat(dto.getIsSupervisor()).isTrue();
    assertThat(dto.getAgencies()).hasSize(1);
  }

  @Test
  void setters_Should_RoundTripAllFields() {
    var dto = new ConsultantResponseDTO();
    dto.setConsultantId("id");
    dto.setFirstName("First");
    dto.setLastName("Last");
    dto.setDisplayName("Display");
    dto.setUsername("username");
    dto.setIsSupervisor(false);
    dto.setAgencies(List.of(new AgencyResponseDTO().id(2L)));

    assertThat(dto.getConsultantId()).isEqualTo("id");
    assertThat(dto.getAgencies()).hasSize(1);
  }

  @Test
  void addAgenciesItem_Should_InitializeList_When_AgenciesIsNull() {
    var dto = new ConsultantResponseDTO();
    dto.setAgencies(null);

    dto.addAgenciesItem(new AgencyResponseDTO().id(1L));

    assertThat(dto.getAgencies()).hasSize(1);
  }

  @Test
  void addAgenciesItem_Should_AppendToExistingList_When_AgenciesAlreadyInitialized() {
    var dto = new ConsultantResponseDTO();

    dto.addAgenciesItem(new AgencyResponseDTO().id(1L));
    dto.addAgenciesItem(new AgencyResponseDTO().id(2L));

    assertThat(dto.getAgencies()).hasSize(2);
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

    assertThat(dto.equals("not a ConsultantResponseDTO")).isFalse();
  }

  @Test
  void toString_Should_ContainFieldValues() {
    var dto = givenAFullyPopulatedDTO();

    var result = dto.toString();

    assertThat(result).contains("Mustermann").contains("max.mustermann");
  }

  @Test
  void equals_Should_ReturnFalse_When_AnyIndividualFieldDiffers() {
    var base = givenAFullyPopulatedDTO();

    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().consultantId("otherId"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().firstName("otherFirstName"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().lastName("otherLastName"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().displayName("otherDisplayName"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().username("otherUsername"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().isSupervisor(false));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().agencies(List.of()));
  }
}
