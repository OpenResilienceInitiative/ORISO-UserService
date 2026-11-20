package de.caritas.cob.userservice.api.adapters.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AskerEmailCollectionCapabilityContractTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void exposesAnExplicitFalseCapabilityButOmitsAnUnspecifiedConsultantCapability() {
    var data = new UserDataResponseDTO();
    assertThat(mapper.valueToTree(data).has("isAskerEmailCollectionAllowed")).isFalse();
    data.setIsAskerEmailCollectionAllowed(false);
    assertThat(mapper.valueToTree(data).path("isAskerEmailCollectionAllowed").isBoolean()).isTrue();
    assertThat(mapper.valueToTree(data).path("isAskerEmailCollectionAllowed").asBoolean())
        .isFalse();
  }

  @Test
  void aCallerCannotSupplyItsOwnReadOnlyCapability() throws Exception {
    var data =
        mapper.readValue("{\"isAskerEmailCollectionAllowed\":true}", UserDataResponseDTO.class);
    assertThat(data.getIsAskerEmailCollectionAllowed()).isNull();
  }
}
