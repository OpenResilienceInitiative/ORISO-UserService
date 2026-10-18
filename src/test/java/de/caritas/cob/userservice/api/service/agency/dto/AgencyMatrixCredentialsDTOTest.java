package de.caritas.cob.userservice.api.service.agency.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AgencyMatrixCredentialsDTOTest {

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void identityOnlyResponseCanBeDeserialized() throws Exception {
    var dto =
        json.readValue("{\"matrixUserId\":\"@agency:matrix\"}", AgencyMatrixCredentialsDTO.class);
    assertThat(dto.getMatrixUserId()).isEqualTo("@agency:matrix");
  }

  @Test
  void oldAgencyResponseDiscardsPasswordWithoutRetainingOrReserializingIt() throws Exception {
    var dto =
        json.readValue(
            "{\"matrixUserId\":\"@agency:matrix\",\"matrixPassword\":\"public-legacy-fixture\"}",
            AgencyMatrixCredentialsDTO.class);
    assertThat(dto.getMatrixUserId()).isEqualTo("@agency:matrix");
    assertThat(dto.toString()).doesNotContain("Password", "public-legacy-fixture");
    assertThat(json.writeValueAsString(dto)).isEqualTo("{\"matrixUserId\":\"@agency:matrix\"}");
  }

  @Test
  void equalityAndHashCodeUseOnlyIdentity() {
    var first = new AgencyMatrixCredentialsDTO();
    first.setMatrixUserId("@agency:matrix");
    var second = new AgencyMatrixCredentialsDTO();
    second.setMatrixUserId("@agency:matrix");
    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    second.setMatrixUserId("@other:matrix");
    assertThat(first).isNotEqualTo(second);
  }
}
