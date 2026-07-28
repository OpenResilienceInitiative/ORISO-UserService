package de.caritas.cob.userservice.api.adapters.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CreateAnonymousEnquiryResponseDTOTest {

  @Test
  void serializesOnlyIdentityCredentialsAndSessionReference() throws Exception {
    var dto =
        new CreateAnonymousEnquiryResponseDTO(
            "anonymous-user", 42L, "access-token", 300, "refresh-token", 600);

    var json = new ObjectMapper().writeValueAsString(dto);

    assertThat(json)
        .contains(
            "\"userName\":\"anonymous-user\"",
            "\"sessionId\":42",
            "\"accessToken\":\"access-token\"",
            "\"refreshToken\":\"refresh-token\"")
        .doesNotContain("rcUserId", "rcToken", "rcGroupId");
  }

  @Test
  void fluentSettersCoverThePublicResponseFields() {
    var dto =
        new CreateAnonymousEnquiryResponseDTO()
            .userName("anonymous-user")
            .sessionId(42L)
            .accessToken("access-token")
            .expiresIn(300)
            .refreshToken("refresh-token")
            .refreshExpiresIn(600);

    assertThat(dto.getUserName()).isEqualTo("anonymous-user");
    assertThat(dto.getSessionId()).isEqualTo(42L);
    assertThat(dto.getAccessToken()).isEqualTo("access-token");
    assertThat(dto.getExpiresIn()).isEqualTo(300);
    assertThat(dto.getRefreshToken()).isEqualTo("refresh-token");
    assertThat(dto.getRefreshExpiresIn()).isEqualTo(600);
  }
}
