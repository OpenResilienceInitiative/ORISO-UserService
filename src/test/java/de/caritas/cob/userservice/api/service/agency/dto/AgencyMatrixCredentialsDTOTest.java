package de.caritas.cob.userservice.api.service.agency.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgencyMatrixCredentialsDTOTest {

  @Test
  void gettersAndSetters_Should_roundTripValues() {
    AgencyMatrixCredentialsDTO dto = new AgencyMatrixCredentialsDTO();
    dto.setMatrixUserId("@agency:matrix.example.com");
    dto.setMatrixPassword("secret");

    assertThat(dto.getMatrixUserId()).isEqualTo("@agency:matrix.example.com");
    assertThat(dto.getMatrixPassword()).isEqualTo("secret");
  }

  @Test
  void equalsAndHashCode_Should_matchForSameValues() {
    AgencyMatrixCredentialsDTO first = credentials("@user:matrix", "password");
    AgencyMatrixCredentialsDTO second = credentials("@user:matrix", "password");

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
  }

  @Test
  void equals_Should_notMatch_When_valuesDiffer() {
    AgencyMatrixCredentialsDTO first = credentials("@user:matrix", "password");
    AgencyMatrixCredentialsDTO second = credentials("@other:matrix", "password");

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void toString_Should_containFieldValues() {
    AgencyMatrixCredentialsDTO dto = credentials("@user:matrix", "password");

    assertThat(dto.toString()).contains("@user:matrix").contains("password");
  }

  private static AgencyMatrixCredentialsDTO credentials(String matrixUserId, String password) {
    AgencyMatrixCredentialsDTO dto = new AgencyMatrixCredentialsDTO();
    dto.setMatrixUserId(matrixUserId);
    dto.setMatrixPassword(password);
    return dto;
  }
}
