package de.caritas.cob.userservice.api.adapters.keycloak.config;

import de.caritas.cob.userservice.api.config.RestTemplateTimeouts;
import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.http.HttpEntity;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.engines.ClientHttpEngineBuilder43;
import org.jboss.resteasy.client.jaxrs.engines.ManualClosingApacheHttpClient43Engine;
import org.jboss.resteasy.client.jaxrs.internal.ClientInvocation;
import org.keycloak.admin.client.ClientBuilderWrapper;
import org.keycloak.admin.client.JacksonProvider;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;

/**
 * Owns the RESTEasy-specific implementation of the pooled Keycloak admin transport.
 *
 * <p>The package-private type deliberately exposes only creation of a configured admin client.
 * RESTEasy's concrete-engine constraint, measurement and timeout policy stay local to the Keycloak
 * adapter.
 */
final class KeycloakAdminClientTransport {

  private static final int CONNECTION_POOL_SIZE = 50;

  private final OutboundHttpMetrics metrics;
  private final Duration connectTimeout;
  private final Duration readTimeout;
  private final Duration connectionCheckoutTimeout;
  private final int connectionPoolSize;

  KeycloakAdminClientTransport(OutboundHttpMetrics metrics) {
    this(
        metrics,
        RestTemplateTimeouts.CONNECT_TIMEOUT,
        RestTemplateTimeouts.READ_TIMEOUT,
        RestTemplateTimeouts.CONNECT_TIMEOUT,
        CONNECTION_POOL_SIZE);
  }

  KeycloakAdminClientTransport(
      OutboundHttpMetrics metrics,
      Duration connectTimeout,
      Duration readTimeout,
      Duration connectionCheckoutTimeout) {
    this(metrics, connectTimeout, readTimeout, connectionCheckoutTimeout, CONNECTION_POOL_SIZE);
  }

  KeycloakAdminClientTransport(
      OutboundHttpMetrics metrics,
      Duration connectTimeout,
      Duration readTimeout,
      Duration connectionCheckoutTimeout,
      int connectionPoolSize) {
    this.metrics = metrics;
    this.connectTimeout = connectTimeout;
    this.readTimeout = readTimeout;
    this.connectionCheckoutTimeout = connectionCheckoutTimeout;
    this.connectionPoolSize = connectionPoolSize;
  }

  Keycloak create(String serverUrl, String realm, KeycloakCustomConfig config) {
    var clientBuilder = (ResteasyClientBuilder) ClientBuilderWrapper.create(null, false);
    clientBuilder
        .connectTimeout(connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .readTimeout(readTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .connectionCheckoutTimeout(connectionCheckoutTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .connectionPoolSize(connectionPoolSize)
        .disableAutomaticRetries();
    var clientHttpEngine =
        (ManualClosingApacheHttpClient43Engine)
            new ClientHttpEngineBuilder43().resteasyClientBuilder(clientBuilder).build();
    clientBuilder.httpEngine(new MeasuredClientEngine(clientHttpEngine, metrics));
    clientBuilder.register(JacksonProvider.class, 100);

    return KeycloakBuilder.builder()
        .serverUrl(serverUrl)
        .realm(realm)
        .username(config.getAdminUsername())
        .password(config.getAdminPassword())
        .clientId(config.getAdminClientId())
        .resteasyClient(clientBuilder.build())
        .build();
  }

  @SuppressWarnings("removal")
  private static final class MeasuredClientEngine extends ManualClosingApacheHttpClient43Engine {

    private final ManualClosingApacheHttpClient43Engine delegate;
    private final OutboundHttpMetrics metrics;
    private final ThreadLocal<OutboundHttpMetrics.OutboundAttempt> currentAttempt =
        new ThreadLocal<>();

    private MeasuredClientEngine(
        ManualClosingApacheHttpClient43Engine delegate, OutboundHttpMetrics metrics) {
      super(delegate.getHttpClient(), false);
      this.delegate = delegate;
      this.metrics = metrics;
      setSslContext(delegate.getSslContext());
      setHostnameVerifier(delegate.getHostnameVerifier());
      setResponseBufferSize(delegate.getResponseBufferSize());
      setChunked(delegate.isChunked());
      setFollowRedirects(delegate.isFollowRedirects());
      if (delegate.getFileUploadMemoryThreshold() != null) {
        setFileUploadMemoryThreshold(delegate.getFileUploadMemoryThreshold());
      }
      if (delegate.getFileUploadTempFileDir() != null) {
        setFileUploadTempFileDir(delegate.getFileUploadTempFileDir());
      }
      this.defaultProxy = delegate.getDefaultProxy();
    }

    @Override
    public Response invoke(Invocation invocation) {
      if (!(invocation instanceof ClientInvocation clientInvocation)) {
        return super.invoke(invocation);
      }

      var initialRequestBytes =
          clientInvocation.getEntity() == null ? 0 : clientInvocation.getHeaders().getLength();
      var attempt =
          metrics.startAttempt("keycloak", clientInvocation.getMethod(), initialRequestBytes);
      currentAttempt.set(attempt);
      try {
        var response = super.invoke(invocation);
        attempt.complete(response.getStatus(), response.getLength());
        return response;
      } catch (RuntimeException | Error failure) {
        attempt.fail();
        throw failure;
      } finally {
        currentAttempt.remove();
      }
    }

    @Override
    protected HttpEntity buildEntity(ClientInvocation invocation) throws IOException {
      var entity = super.buildEntity(invocation);
      var attempt = currentAttempt.get();
      if (attempt != null) {
        attempt.recordRequestPayload(entity.getContentLength());
      }
      return entity;
    }

    @Override
    public void close() {
      delegate.close();
    }
  }
}
