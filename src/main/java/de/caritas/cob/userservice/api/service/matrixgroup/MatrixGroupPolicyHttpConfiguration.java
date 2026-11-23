package de.caritas.cob.userservice.api.service.matrixgroup;

import de.caritas.cob.userservice.api.config.RestTemplateTimeouts;
import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
public class MatrixGroupPolicyHttpConfiguration {
  /** Let the bounded reader reject non200 without eagerly consuming private error bodies. */
  @Bean("matrixGroupHistoryRestTemplate")
  public RestTemplate historyTransport(
      RestTemplateBuilder builder, OutboundHttpMetrics outboundHttpMetrics) {
    var restTemplate =
        builder
            .requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk())
            .redirects(HttpRedirects.DONT_FOLLOW)
            .connectTimeout(RestTemplateTimeouts.CONNECT_TIMEOUT)
            .readTimeout(RestTemplateTimeouts.READ_TIMEOUT)
            .errorHandler(new NoOpResponseErrorHandler())
            .build();
    outboundHttpMetrics.customize(restTemplate);
    return restTemplate;
  }
}
