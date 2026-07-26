package de.caritas.cob.userservice.api.config.apiclient;

import static de.caritas.cob.userservice.api.config.RestTemplateTimeouts.CONNECT_TIMEOUT;
import static de.caritas.cob.userservice.api.config.RestTemplateTimeouts.READ_TIMEOUT;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import de.caritas.cob.userservice.liveservice.generated.ApiClient;
import de.caritas.cob.userservice.liveservice.generated.ApiException;
import de.caritas.cob.userservice.liveservice.generated.ApiResponse;
import de.caritas.cob.userservice.liveservice.generated.web.LiveControllerApi;
import de.caritas.cob.userservice.liveservice.generated.web.model.LiveEventMessage;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LiveServiceApiControllerFactory {

  @Value("${live.service.api.url}")
  private String liveServiceApiUrl;

  private final ObjectMapper objectMapper;
  private final OutboundHttpMetrics outboundHttpMetrics;

  public LiveControllerApi createControllerApi() {
    var apiClient = createApiClient();

    return new MeasuredLiveControllerApi(
        apiClient,
        objectMapper,
        outboundHttpMetrics,
        URI.create(liveServiceApiUrl + "/liveevent/send"));
  }

  ApiClient createApiClient() {
    return new ApiClient(HttpClient.newBuilder(), objectMapper, liveServiceApiUrl)
        .setConnectTimeout(CONNECT_TIMEOUT)
        .setReadTimeout(READ_TIMEOUT);
  }

  private static final class MeasuredLiveControllerApi extends LiveControllerApi {

    private final ObjectMapper objectMapper;
    private final OutboundHttpMetrics outboundHttpMetrics;
    private final URI endpoint;

    private MeasuredLiveControllerApi(
        ApiClient apiClient,
        ObjectMapper objectMapper,
        OutboundHttpMetrics outboundHttpMetrics,
        URI endpoint) {
      super(apiClient);
      this.objectMapper = objectMapper;
      this.outboundHttpMetrics = outboundHttpMetrics;
      this.endpoint = endpoint;
    }

    @Override
    public CompletableFuture<Void> sendLiveEvent(
        LiveEventMessage liveEventMessage, Map<String, String> headers) throws ApiException {
      var measurement =
          outboundHttpMetrics.startHttpCall(endpoint, "POST", serializedSize(liveEventMessage));
      try {
        return super.sendLiveEventWithHttpInfo(liveEventMessage, headers)
            .whenComplete(
                (response, failure) -> completeMeasurement(measurement, response, failure))
            .thenApply(ignored -> null);
      } catch (ApiException | RuntimeException failure) {
        completeMeasurement(measurement, null, failure);
        throw failure;
      }
    }

    private long serializedSize(LiveEventMessage liveEventMessage) {
      try {
        return objectMapper.writeValueAsBytes(liveEventMessage).length;
      } catch (RuntimeException | java.io.IOException serializationFailure) {
        return -1;
      }
    }

    private void completeMeasurement(
        OutboundHttpMetrics.OutboundHttpCall measurement,
        ApiResponse<Void> response,
        Throwable failure) {
      if (response != null) {
        measurement.completeWithStatus(response.getStatusCode(), responseBytes(response));
        return;
      }
      var apiException = findApiException(failure);
      if (apiException != null && apiException.getCode() > 0) {
        measurement.completeWithStatus(
            apiException.getCode(), utf8Size(apiException.getResponseBody()));
      } else {
        measurement.completeWithTransportError();
      }
    }

    private ApiException findApiException(Throwable failure) {
      var current = failure;
      while (current != null) {
        if (current instanceof ApiException apiException) {
          return apiException;
        }
        var cause = current.getCause();
        if (cause == current) {
          return null;
        }
        current = cause;
      }
      return null;
    }

    private long responseBytes(ApiResponse<Void> response) {
      if (response.getStatusCode() == 204) {
        return 0;
      }
      return response.getHeaders().entrySet().stream()
          .filter(entry -> entry.getKey().equalsIgnoreCase("content-length"))
          .map(Map.Entry::getValue)
          .flatMap(List::stream)
          .findFirst()
          .map(this::parseLength)
          .orElse(-1L);
    }

    private long parseLength(String value) {
      try {
        return Long.parseLong(value);
      } catch (NumberFormatException invalidLength) {
        return -1;
      }
    }

    private long utf8Size(String body) {
      return body == null ? -1 : body.getBytes(StandardCharsets.UTF_8).length;
    }
  }
}
