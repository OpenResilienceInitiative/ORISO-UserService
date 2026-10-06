package de.caritas.cob.userservice.api.service.matrixgroup;

import de.caritas.cob.userservice.api.config.RestTemplateTimeouts;
import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
public class MatrixGroupPolicyHttpConfiguration {
  /** Let the bounded reader reject non200 without eagerly consuming private error bodies. */
  @Bean("matrixGroupHistoryRestTemplate")
  public RestTemplate historyTransport(RestTemplateBuilder builder, OutboundHttpMetrics metrics) {
    var transport =
        builder
            .requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk())
            .connectTimeout(RestTemplateTimeouts.CONNECT_TIMEOUT)
            .readTimeout(RestTemplateTimeouts.READ_TIMEOUT)
            .errorHandler(new NoOpResponseErrorHandler())
            .build();
    metrics.customize(transport);
    return transport;
  }
}
