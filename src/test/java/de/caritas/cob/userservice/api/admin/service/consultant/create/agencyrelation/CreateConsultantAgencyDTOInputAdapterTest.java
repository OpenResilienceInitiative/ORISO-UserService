package de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import org.junit.jupiter.api.Test;

class CreateConsultantAgencyDTOInputAdapterTest {

  @Test
  void getters_Should_returnExpectedValues_When_dtoFieldsAreSet() {
    CreateConsultantAgencyDTO dto =
        new CreateConsultantAgencyDTO().agencyId(15L).roleSetKey("main");

    ConsultantAgencyCreationInput input =
        new CreateConsultantAgencyDTOInputAdapter("consultant-1", dto);

    assertThat(input.getConsultantId(), is("consultant-1"));
    assertThat(input.getAgencyId(), is(15L));
    assertThat(input.getRoleSetNames(), contains("main"));
    assertThat(input.getAdditionalAgencyIds(), contains(15L));
    assertThat(input.getCreateDate(), notNullValue());
    assertThat(input.getUpdateDate(), notNullValue());
  }
}
