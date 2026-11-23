package de.caritas.cob.userservice.api.config.apiclient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.util.ReflectionTestUtils.setField;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;

import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class TopicServiceApiControllerFactoryTest {

  private final RestTemplate restTemplate = new RestTemplate();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
  private final TopicServiceApiControllerFactory factory = new TopicServiceApiControllerFactory();

  @Test
  void blankScopedUrlOnlyDisablesTheScopedClient() {
    setField(factory, "restTemplate", restTemplate);
    setField(factory, "topicServiceApiUrl", "http://consultingtypeservice.dev:8080");
    setField(factory, "scopedTopicServiceApiUrl", " ");
    server
        .expect(requestTo("http://consultingtypeservice.dev:8080/topic/public"))
        .andRespond(withNoContent());

    assertThatThrownBy(factory::createScopedControllerApi)
        .isInstanceOf(ServiceUnavailableException.class);
    factory.createControllerApi().getAllActiveTopics();

    server.verify();
  }

  @Test
  void scopedClientAppendsTopicRouteToConfiguredPrefix() {
    setField(factory, "restTemplate", restTemplate);
    setField(factory, "scopedTopicServiceApiUrl", "https://catalogue-service:8443/prefix");
    server
        .expect(requestTo("https://catalogue-service:8443/prefix/topic/public"))
        .andRespond(withNoContent());

    factory.createScopedControllerApi().getAllActiveTopics();

    server.verify();
  }
}
