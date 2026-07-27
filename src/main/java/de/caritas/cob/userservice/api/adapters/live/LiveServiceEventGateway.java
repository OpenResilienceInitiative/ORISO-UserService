package de.caritas.cob.userservice.api.adapters.live;

import static de.caritas.cob.userservice.api.config.RestTemplateTimeouts.CONNECT_TIMEOUT;
import static de.caritas.cob.userservice.api.config.RestTemplateTimeouts.READ_TIMEOUT;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import de.caritas.cob.userservice.api.port.out.LiveEventGateway;
import de.caritas.cob.userservice.api.service.liveevents.LiveEvent;
import de.caritas.cob.userservice.liveservice.generated.ApiClient;
import de.caritas.cob.userservice.liveservice.generated.web.LiveControllerApi;
import de.caritas.cob.userservice.liveservice.generated.web.model.EventType;
import de.caritas.cob.userservice.liveservice.generated.web.model.LiveEventMessage;
import de.caritas.cob.userservice.liveservice.generated.web.model.StatusSource;
import java.net.http.HttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Long-lived LiveService adapter that owns transport setup, mapping and delivery observation. */
@Slf4j
@Component
public class LiveServiceEventGateway implements LiveEventGateway {

  private static final String DEPENDENCY = "live-service";
  private static final String METHOD = "post";

  private final LiveControllerApi liveControllerApi;
  private final ObjectMapper objectMapper;
  private final OutboundHttpMetrics outboundHttpMetrics;

  @Autowired
  public LiveServiceEventGateway(
      @Value("${live.service.api.url}") String liveServiceApiUrl,
      ObjectMapper objectMapper,
      OutboundHttpMetrics outboundHttpMetrics) {
    this(createController(liveServiceApiUrl, objectMapper), objectMapper, outboundHttpMetrics);
  }

  LiveServiceEventGateway(
      LiveControllerApi liveControllerApi,
      ObjectMapper objectMapper,
      OutboundHttpMetrics outboundHttpMetrics) {
    this.liveControllerApi = liveControllerApi;
    this.objectMapper = objectMapper;
    this.outboundHttpMetrics = outboundHttpMetrics;
  }

  @Override
  public void send(LiveEvent event) {
    var message =
        new LiveEventMessage()
            .eventType(EventType.valueOf(event.type().name()))
            .userIds(event.recipientIds());
    if (event.finishConversationPhase() != null) {
      message.eventContent(
          new StatusSource()
              .finishConversationPhase(
                  StatusSource.FinishConversationPhaseEnum.valueOf(
                      event.finishConversationPhase().name())));
    }

    try {
      var requestBytes = objectMapper.writeValueAsBytes(message).length;
      outboundHttpMetrics
          .observeAsyncCall(
              DEPENDENCY, METHOD, requestBytes, () -> liveControllerApi.sendLiveEvent(message))
          .whenComplete(
              (ignored, failure) -> {
                if (failure != null) {
                  log.error("Unable to deliver live event", failure);
                }
              });
    } catch (JsonProcessingException failure) {
      log.error("Unable to serialize live event", failure);
    }
  }

  private static LiveControllerApi createController(
      String liveServiceApiUrl, ObjectMapper objectMapper) {
    var apiClient =
        new ApiClient(HttpClient.newBuilder(), objectMapper, liveServiceApiUrl)
            .setConnectTimeout(CONNECT_TIMEOUT)
            .setReadTimeout(READ_TIMEOUT);
    return new LiveControllerApi(apiClient);
  }
}
