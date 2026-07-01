package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

  // -------------------------------------------------------------------------
  // uploadFile – error paths
  // -------------------------------------------------------------------------

  @Test
  void uploadFile_Should_ThrowException_When_ResponseBodyIsNull() {
    var file = new MockMultipartFile("file", "test.txt", "text/plain", "content".getBytes());

    when(restTemplate.postForEntity(
            eq(BASE_URL + "/_matrix/media/r0/upload"), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.status(HttpStatus.OK).build());

    assertThatThrownBy(() -> matrixMediaClient.uploadFile(file, ROOM_ID, ACCESS_TOKEN))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Failed to upload file");
  }

  @Test
  void uploadFile_Should_ThrowException_When_ContentUriIsMissing() {
    var file = new MockMultipartFile("file", "test.txt", "text/plain", "content".getBytes());

    when(restTemplate.postForEntity(
            eq(BASE_URL + "/_matrix/media/r0/upload"), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(new ResponseEntity<>(Map.of("some_other_key", "value"), HttpStatus.OK));

    assertThatThrownBy(() -> matrixMediaClient.uploadFile(file, ROOM_ID, ACCESS_TOKEN))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Failed to upload file");
  }

  @Test
  void uploadFile_Should_ThrowException_When_RestTemplateThrows() {
    var file = new MockMultipartFile("file", "test.txt", "text/plain", "content".getBytes());

    when(restTemplate.postForEntity(any(), any(HttpEntity.class), eq(Map.class)))
        .thenThrow(new RuntimeException("Connection refused"));

    assertThatThrownBy(() -> matrixMediaClient.uploadFile(file, ROOM_ID, ACCESS_TOKEN))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Failed to upload file");
  }

  // -------------------------------------------------------------------------
  // uploadFile – MIME-type-to-msgtype mapping
  // -------------------------------------------------------------------------

  @Test
  void uploadFile_Should_UseImageMsgtype_When_FileIsImage() {
    var file = new MockMultipartFile("file", "photo.png", "image/png", "imagedata".getBytes());

    when(restTemplate.postForEntity(
            eq(BASE_URL + "/_matrix/media/r0/upload"), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(
            new ResponseEntity<>(
                Map.of("content_uri", "mxc://matrix.local/img-id"), HttpStatus.OK));
    when(restTemplate.exchange(
            contains("/_matrix/client/r0/rooms/" + ROOM_ID + "/send/m.room.message/"),
            eq(HttpMethod.PUT),
            any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(new ResponseEntity<>(Map.of("event_id", "$event"), HttpStatus.OK));

    matrixMediaClient.uploadFile(file, ROOM_ID, ACCESS_TOKEN);

    var messageCaptor = ArgumentCaptor.forClass(HttpEntity.class);
    verify(restTemplate)
        .exchange(
            contains("/_matrix/client/r0/rooms/" + ROOM_ID + "/send/m.room.message/"),
            eq(HttpMethod.PUT),
            messageCaptor.capture(),
            eq(Map.class));

    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) messageCaptor.getValue().getBody();
    assertThat(body).containsEntry("msgtype", "m.image");
  }

  @Test
  void uploadFile_Should_UseVideoMsgtype_When_FileIsVideo() {
    var file = new MockMultipartFile("file", "video.mp4", "video/mp4", "videodata".getBytes());

    when(restTemplate.postForEntity(
            eq(BASE_URL + "/_matrix/media/r0/upload"), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(
            new ResponseEntity<>(
                Map.of("content_uri", "mxc://matrix.local/vid-id"), HttpStatus.OK));
    when(restTemplate.exchange(
            contains("/_matrix/client/r0/rooms/" + ROOM_ID + "/send/m.room.message/"),
            eq(HttpMethod.PUT),
            any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(new ResponseEntity<>(Map.of("event_id", "$event"), HttpStatus.OK));

    matrixMediaClient.uploadFile(file, ROOM_ID, ACCESS_TOKEN);

    var messageCaptor = ArgumentCaptor.forClass(HttpEntity.class);
    verify(restTemplate)
        .exchange(
            contains("/_matrix/client/r0/rooms/" + ROOM_ID + "/send/m.room.message/"),
            eq(HttpMethod.PUT),
            messageCaptor.capture(),
            eq(Map.class));

    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) messageCaptor.getValue().getBody();
    assertThat(body).containsEntry("msgtype", "m.video");
  }

  @Test
  void uploadFile_Should_UseAudioMsgtype_When_FileIsAudio() {
    var file = new MockMultipartFile("file", "audio.mp3", "audio/mpeg", "audiodata".getBytes());

    when(restTemplate.postForEntity(
            eq(BASE_URL + "/_matrix/media/r0/upload"), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(
            new ResponseEntity<>(
                Map.of("content_uri", "mxc://matrix.local/aud-id"), HttpStatus.OK));
    when(restTemplate.exchange(
            contains("/_matrix/client/r0/rooms/" + ROOM_ID + "/send/m.room.message/"),
            eq(HttpMethod.PUT),
            any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(new ResponseEntity<>(Map.of("event_id", "$event"), HttpStatus.OK));

    matrixMediaClient.uploadFile(file, ROOM_ID, ACCESS_TOKEN);

    var messageCaptor = ArgumentCaptor.forClass(HttpEntity.class);
    verify(restTemplate)
        .exchange(
            contains("/_matrix/client/r0/rooms/" + ROOM_ID + "/send/m.room.message/"),
            eq(HttpMethod.PUT),
            messageCaptor.capture(),
            eq(Map.class));

    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) messageCaptor.getValue().getBody();
    assertThat(body).containsEntry("msgtype", "m.audio");
  }

  @Test
  void uploadFile_Should_UseFileMsgtype_When_FileHasGenericContentType() {
    var file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "pdfdata".getBytes());

    when(restTemplate.postForEntity(
            eq(BASE_URL + "/_matrix/media/r0/upload"), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(
            new ResponseEntity<>(
                Map.of("content_uri", "mxc://matrix.local/doc-id"), HttpStatus.OK));
    when(restTemplate.exchange(
            contains("/_matrix/client/r0/rooms/" + ROOM_ID + "/send/m.room.message/"),
            eq(HttpMethod.PUT),
            any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(new ResponseEntity<>(Map.of("event_id", "$event"), HttpStatus.OK));

    matrixMediaClient.uploadFile(file, ROOM_ID, ACCESS_TOKEN);

    var messageCaptor = ArgumentCaptor.forClass(HttpEntity.class);
    verify(restTemplate)
        .exchange(
            contains("/_matrix/client/r0/rooms/" + ROOM_ID + "/send/m.room.message/"),
            eq(HttpMethod.PUT),
            messageCaptor.capture(),
            eq(Map.class));

    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) messageCaptor.getValue().getBody();
    assertThat(body).containsEntry("msgtype", "m.file");
  }

  @Test
  void uploadFile_Should_StillReturnSuccess_When_SendFileMessageFails() {
    var file = new MockMultipartFile("file", "test.txt", "text/plain", "content".getBytes());

    when(restTemplate.postForEntity(
            eq(BASE_URL + "/_matrix/media/r0/upload"), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(
            new ResponseEntity<>(
                Map.of("content_uri", "mxc://matrix.local/media-id"), HttpStatus.OK));
    when(restTemplate.exchange(
            contains("/_matrix/client/r0/rooms/" + ROOM_ID + "/send/m.room.message/"),
            eq(HttpMethod.PUT),
            any(HttpEntity.class),
            eq(Map.class)))
        .thenThrow(new RuntimeException("Matrix room not found"));

    // sendFileMessage swallows its own exception; uploadFile still reports success
    var result = matrixMediaClient.uploadFile(file, ROOM_ID, ACCESS_TOKEN);

    assertThat(result).containsEntry("success", true);
  }

  // -------------------------------------------------------------------------
  // downloadFile – error paths
  // -------------------------------------------------------------------------

  @Test
  void downloadFile_Should_ThrowException_When_ResponseBodyIsNull() {
    when(restTemplate.exchange(
            eq(BASE_URL + "/_matrix/media/r0/download/matrix.local/media-id"),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(byte[].class)))
        .thenReturn(ResponseEntity.status(HttpStatus.OK).build());

    assertThatThrownBy(
            () -> matrixMediaClient.downloadFile("matrix.local", "media-id", ACCESS_TOKEN))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Failed to download file");
  }

  @Test
  void downloadFile_Should_ThrowException_When_RestTemplateThrows() {
    when(restTemplate.exchange(
            eq(BASE_URL + "/_matrix/media/r0/download/matrix.local/media-id"),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(byte[].class)))
        .thenThrow(new RuntimeException("Connection refused"));

    assertThatThrownBy(
            () -> matrixMediaClient.downloadFile("matrix.local", "media-id", ACCESS_TOKEN))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Failed to download file");
  }
}
