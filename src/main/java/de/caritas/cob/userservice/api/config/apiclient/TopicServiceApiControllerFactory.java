package de.caritas.cob.userservice.api.config.apiclient;

import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.topicservice.generated.web.TopicControllerApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class TopicServiceApiControllerFactory {

  // The topic API is served by the ConsultingTypeService; no environment ever set a separate URL.
  @Value("${consulting.type.service.api.url}")
  private String topicServiceApiUrl;

  // The short in-cluster hostname lets ConsultingTypeService resolve the server-supplied
  // tenant header. A dotted host would instead be treated as a public tenant subdomain.
  @Value("${consulting.type.service.scoped.api.url:}")
  private String scopedTopicServiceApiUrl;

  @Autowired private RestTemplate restTemplate;

  public TopicControllerApi createControllerApi() {
    return controllerFor(topicServiceApiUrl);
  }

  public TopicControllerApi createScopedControllerApi() {
    if (scopedTopicServiceApiUrl.isBlank()) {
      throw new ServiceUnavailableException("Scoped topic lookup is not configured");
    }
    return controllerFor(scopedTopicServiceApiUrl);
  }

  private TopicControllerApi controllerFor(String baseUrl) {
    var apiClient = new TopicServiceApiClient(restTemplate).setBasePath(baseUrl);
    TopicControllerApi controllerApi = new TopicControllerApi(apiClient);
    controllerApi.setApiClient(apiClient);
    return controllerApi;
  }
}
