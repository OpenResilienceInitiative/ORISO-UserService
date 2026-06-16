package de.caritas.cob.userservice.api.adapters.matrix;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MatrixSynapseServiceTest {

  private static final String MATRIX_USER_ID = "@seeker:matrix.example.com";
  private static final String ACCESS_TOKEN = "syt_admin_impersonation_token";

  @Mock private MatrixConfig matrixConfig;
  @Mock private RestTemplate restTemplate;

  @InjectMocks private MatrixSynapseService matrixSynapseService;

  @BeforeEach
  void setUp() {
    when(matrixConfig.getAdminUsername()).thenReturn("admin");
    when(matrixConfig.getAdminPassword()).thenReturn("admin-secret");
    when(matrixConfig.getApiUrl(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(
            invocation -> "https://matrix.example.com" + invocation.getArgument(0, String.class));
  }

  /** US-C01: Synapse admin impersonation login must not require stored user passwords. */
  @Test
  void loginUserViaAdmin_Should_ReturnAccessToken_When_AdminLoginSucceeds() {
    when(restTemplate.postForEntity(
            eq("https://matrix.example.com/_matrix/client/r0/login"),
            any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("access_token", "admin-token")));

    when(restTemplate.postForEntity(
            org.mockito.ArgumentMatchers.contains("/_synapse/admin/v1/users/"),
            any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("access_token", ACCESS_TOKEN)));

    String token = matrixSynapseService.loginUserViaAdmin(MATRIX_USER_ID);

    assertThat(token, is(ACCESS_TOKEN));
    assertThat(matrixSynapseService.loginUserViaAdmin(MATRIX_USER_ID), is(ACCESS_TOKEN));
  }

  @Test
  void loginUserViaAdmin_Should_ReturnNull_When_MatrixUserIdMissing() {
    assertThat(matrixSynapseService.loginUserViaAdmin(null), nullValue());
    assertThat(matrixSynapseService.loginUserViaAdmin(""), nullValue());
  }

  @Test
  void loginUserViaAdmin_Should_ReturnNull_When_AdminCredentialsMissing() {
    when(matrixConfig.getAdminUsername()).thenReturn("");
    when(matrixConfig.getAdminPassword()).thenReturn("");

    assertThat(matrixSynapseService.loginUserViaAdmin(MATRIX_USER_ID), nullValue());
  }
}
