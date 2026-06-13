package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class MatrixMediaClientTest {

  private static final String BASE_URL = "http://matrix.local";
  private static final String ACCESS_TOKEN = "access-token";
  private static final String ROOM_ID = "!room:matrix.local";

  @Mock private RestTemplate restTemplate;

  private MatrixMediaClient matrixMediaClient;

  @BeforeEach
  void setUp() {
    var matrixConfig = new MatrixConfig();
    matrixConfig.setApiUrl(BASE_URL);
    matrixMediaClient = new MatrixMediaClient(matrixConfig, restTemplate);
  }

  @Test
  void uploadFile_Should_UploadMediaAndSendFileMessage() {
    var file = new MockMultipartFile("file", "test.txt", "text/plain", "content".getBytes());
    var uploadResponse = Map.of("content_uri", "mxc://matrix.local/media-id");
    var sendResponse = Map.of("event_id", "$event");

    when(restTemplate.postForEntity(
            eq(BASE_URL + "/_matrix/media/r0/upload"), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(new ResponseEntity<>(uploadResponse, HttpStatus.OK));
    when(restTemplate.exchange(
            org.mockito.ArgumentMatchers.contains(
                "/_matrix/client/r0/rooms/" + ROOM_ID + "/send/m.room.message/"),
            eq(HttpMethod.PUT),
            any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(new ResponseEntity<>(sendResponse, HttpStatus.OK));

    var result = matrixMediaClient.uploadFile(file, ROOM_ID, ACCESS_TOKEN);

    assertThat(result)
        .containsEntry("success", true)
        .containsEntry("content_uri", "mxc://matrix.local/media-id")
        .containsEntry("file_name", "test.txt")
        .containsEntry("file_size", 7L);
  }

  @Test
  void downloadFile_Should_ReturnDownloadedBytes() {
    byte[] expectedBytes = "content".getBytes();

    when(restTemplate.exchange(
            eq(BASE_URL + "/_matrix/media/r0/download/matrix.local/media-id"),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(byte[].class)))
        .thenReturn(new ResponseEntity<>(expectedBytes, HttpStatus.OK));

    byte[] result = matrixMediaClient.downloadFile("matrix.local", "media-id", ACCESS_TOKEN);

    assertThat(result).isEqualTo(expectedBytes);
    verify(restTemplate)
        .exchange(
            eq(BASE_URL + "/_matrix/media/r0/download/matrix.local/media-id"),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(byte[].class));
  }
}
