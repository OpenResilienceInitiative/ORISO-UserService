package de.caritas.cob.userservice.api.adapters.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatrixMediaClient {

  private static final String ENDPOINT_MEDIA_UPLOAD = "/_matrix/media/r0/upload";

  private final MatrixConfig matrixConfig;
  private final RestTemplate restTemplate;

  public Map<String, Object> uploadFile(MultipartFile file, String roomId, String accessToken) {
    try {
      String url = matrixConfig.getApiUrl(ENDPOINT_MEDIA_UPLOAD);

      log.info(
          "📤 Uploading file to Matrix: {} ({}bytes)", file.getOriginalFilename(), file.getSize());

      HttpHeaders headers = new HttpHeaders();
      headers.set("Authorization", "Bearer " + accessToken);
      headers.setContentType(MediaType.parseMediaType(file.getContentType()));

      HttpEntity<byte[]> requestEntity = new HttpEntity<>(file.getBytes(), headers);

      ResponseEntity<Map> response = restTemplate.postForEntity(url, requestEntity, Map.class);

      if (response.getBody() != null && response.getBody().containsKey("content_uri")) {
        String contentUri = (String) response.getBody().get("content_uri");
        log.info("✅ File uploaded successfully: {}", contentUri);

        sendFileMessage(
            roomId,
            file.getOriginalFilename(),
            contentUri,
            file.getContentType(),
            file.getSize(),
            accessToken);

        return Map.of(
            "success",
            true,
            "content_uri",
            contentUri,
            "file_name",
            file.getOriginalFilename(),
            "file_size",
            file.getSize());
      }

      throw new RuntimeException("No content_uri in Matrix upload response");

    } catch (Exception e) {
      log.error("❌ Failed to upload file to Matrix", e);
      throw new RuntimeException("Failed to upload file: " + e.getMessage(), e);
    }
  }

  public byte[] downloadFile(String serverName, String mediaId, String accessToken) {
    try {
      String url =
          matrixConfig.getApiUrl("/_matrix/media/r0/download/" + serverName + "/" + mediaId);

      log.info("📥 Downloading file from Matrix: {}/{}", serverName, mediaId);

      HttpHeaders headers = new HttpHeaders();
      headers.set("Authorization", "Bearer " + accessToken);

      HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

      ResponseEntity<byte[]> response =
          restTemplate.exchange(url, HttpMethod.GET, requestEntity, byte[].class);

      if (response.getBody() != null) {
        log.info("✅ File downloaded successfully: {} bytes", response.getBody().length);
        return response.getBody();
      }

      throw new RuntimeException("No file data in Matrix download response");

    } catch (Exception e) {
      log.error("❌ Failed to download file from Matrix", e);
      throw new RuntimeException("Failed to download file: " + e.getMessage(), e);
    }
  }

  private Map<String, Object> sendFileMessage(
      String roomId,
      String fileName,
      String contentUri,
      String mimeType,
      long fileSize,
      String accessToken) {
    try {
      var headers = getClientHttpHeaders(accessToken);
      headers.setContentType(MediaType.APPLICATION_JSON);

      String msgtype = "m.file";
      if (mimeType != null) {
        if (mimeType.startsWith("image/")) {
          msgtype = "m.image";
        } else if (mimeType.startsWith("video/")) {
          msgtype = "m.video";
        } else if (mimeType.startsWith("audio/")) {
          msgtype = "m.audio";
        }
      }

      var messageBody = new HashMap<String, Object>();
      messageBody.put("msgtype", msgtype);
      messageBody.put("body", fileName);
      messageBody.put("url", contentUri);

      var info = new HashMap<String, Object>();
      info.put("size", fileSize);
      if (mimeType != null) {
        info.put("mimetype", mimeType);
      }
      messageBody.put("info", info);

      HttpEntity<Map<String, Object>> request = new HttpEntity<>(messageBody, headers);

      String txnId = UUID.randomUUID().toString();
      var url =
          matrixConfig.getApiUrl(
              "/_matrix/client/r0/rooms/" + roomId + "/send/m.room.message/" + txnId);

      log.info("📨 Sending file message to Matrix room: {} (type: {})", roomId, msgtype);

      var response = restTemplate.exchange(url, HttpMethod.PUT, request, Map.class);

      log.info("✅ File message sent successfully");
      return response.getBody();
    } catch (Exception ex) {
      log.error(
          "Matrix Error: Could not send file message to room ({}). Reason: {}",
          roomId,
          ex.getMessage());
      return Map.of("error", ex.getMessage());
    }
  }

  private HttpHeaders getClientHttpHeaders(String accessToken) {
    var headers = new HttpHeaders();
    headers.set("Authorization", "Bearer " + accessToken);
    return headers;
  }
}
