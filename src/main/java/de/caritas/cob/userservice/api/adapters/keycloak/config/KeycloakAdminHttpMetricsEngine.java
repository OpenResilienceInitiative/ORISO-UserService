package de.caritas.cob.userservice.api.adapters.keycloak.config;

import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import org.jboss.resteasy.client.jaxrs.engines.ManualClosingApacheHttpClient43Engine;
import org.jboss.resteasy.client.jaxrs.internal.ClientInvocation;

/**
 * Measures Keycloak's direct RESTEasy transport without exposing request paths or payload content.
 *
 * <p>The engine boundary observes every response and transport failure. The writer interceptor
 * counts the bytes produced by Keycloak's configured provider instead of estimating object sizes.
 */
@SuppressWarnings("removal")
final class KeycloakAdminHttpMetricsEngine extends ManualClosingApacheHttpClient43Engine
    implements WriterInterceptor {

  private static final String REQUEST_URI_PROPERTY =
      KeycloakAdminHttpMetricsEngine.class.getName() + ".requestUri";

  private final ManualClosingApacheHttpClient43Engine delegate;
  private final OutboundHttpMetrics outboundHttpMetrics;

  KeycloakAdminHttpMetricsEngine(
      ManualClosingApacheHttpClient43Engine delegate, OutboundHttpMetrics outboundHttpMetrics) {
    super(delegate.getHttpClient(), false);
    this.delegate = delegate;
    this.outboundHttpMetrics = outboundHttpMetrics;
  }

  @Override
  public Response invoke(Invocation invocation) {
    if (!(invocation instanceof ClientInvocation clientInvocation)) {
      return delegate.invoke(invocation);
    }

    var uri = clientInvocation.getUri();
    var requestBytes = clientInvocation.getEntity() == null ? 0 : -1;
    var measurement =
        outboundHttpMetrics.startHttpCall(uri, clientInvocation.getMethod(), requestBytes);
    clientInvocation.getMutableProperties().put(REQUEST_URI_PROPERTY, uri);

    try {
      var response = delegate.invoke(invocation);
      var responseBytes = response.getLength();
      var responseStatus = response.getStatus();
      if (responseBytes < 0
          && (clientInvocation.getMethod().equalsIgnoreCase("HEAD")
              || responseStatus == 204
              || responseStatus == 304)) {
        responseBytes = 0;
      }
      measurement.completeWithStatus(responseStatus, responseBytes);
      return response;
    } catch (RuntimeException exception) {
      measurement.completeWithTransportError();
      throw exception;
    }
  }

  @Override
  public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
    var requestUri = context.getProperty(REQUEST_URI_PROPERTY);
    if (!(requestUri instanceof URI uri)) {
      context.proceed();
      return;
    }

    var originalOutput = context.getOutputStream();
    var countingOutput = new CountingOutputStream(originalOutput);
    context.setOutputStream(countingOutput);
    try {
      context.proceed();
      outboundHttpMetrics.recordRequestPayload(uri, countingOutput.count());
    } finally {
      context.setOutputStream(originalOutput);
    }
  }

  @Override
  public SSLContext getSslContext() {
    return delegate.getSslContext();
  }

  @Override
  public HostnameVerifier getHostnameVerifier() {
    return delegate.getHostnameVerifier();
  }

  @Override
  public boolean isFollowRedirects() {
    return delegate.isFollowRedirects();
  }

  @Override
  public void setFollowRedirects(boolean followRedirects) {
    delegate.setFollowRedirects(followRedirects);
  }

  @Override
  public boolean isClosed() {
    return delegate.isClosed();
  }

  @Override
  public void close() {
    delegate.close();
  }

  private static final class CountingOutputStream extends FilterOutputStream {

    private long count;

    private CountingOutputStream(OutputStream outputStream) {
      super(outputStream);
    }

    @Override
    public void write(int value) throws IOException {
      out.write(value);
      count++;
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      out.write(bytes, offset, length);
      count += length;
    }

    private long count() {
      return count;
    }
  }
}
