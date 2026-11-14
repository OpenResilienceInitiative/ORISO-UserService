package de.caritas.cob.userservice.api.service.calendar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.concurrent.Cancellable;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/**
 * Reads a calendar over https (ADR-032, ORISO-UserService#1645). The connection goes to the
 * addresses the validator approved, whatever the name resolves to by now, while TLS still checks
 * the certificate against the host name. No redirect is followed (it would lead to an address
 * nobody checked), nothing is retried, and time and size are bounded.
 */
@Component
public class HttpCalendarImportFetcher implements CalendarImportFetcher {

  static final int MAX_BYTES = 2 * 1024 * 1024;
  private static final Timeout TIMEOUT = Timeout.ofSeconds(10);
  private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(30);

  @Override
  public byte[] fetch(URI address, List<InetAddress> validated) {
    var pinned = validated.toArray(InetAddress[]::new);
    var connections =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(new PinnedResolver(pinned))
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(TIMEOUT)
                    .setSocketTimeout(TIMEOUT)
                    .build())
            .build();
    try (var client =
        HttpClients.custom()
            .setConnectionManager(connections)
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .disableCookieManagement()
            .disableAuthCaching()
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setConnectionRequestTimeout(TIMEOUT)
                    .setResponseTimeout(TIMEOUT)
                    .setRedirectsEnabled(false)
                    .build())
            .build()) {
      var request = new HttpGet(address);
      request.setHeader(HttpHeaders.ACCEPT, "text/calendar, */*;q=0.1");
      request.setHeader(HttpHeaders.USER_AGENT, "ORISO-Calendar-Import");
      return client.execute(request, response -> bodyOf(request, response));
    } catch (IOException unreachable) {
      throw new CalendarImportException(CalendarImportError.UNREACHABLE);
    }
  }

  /**
   * Only a plain 200 carries a calendar; a redirect is refused like any other answer. Giving up on
   * a body cancels the request first: closing the stream would otherwise read the rest of it, with
   * no deadline, before the connection is let go.
   */
  static byte[] bodyOf(Cancellable request, ClassicHttpResponse response) throws IOException {
    if (response.getCode() != 200 || response.getEntity() == null) {
      throw new CalendarImportException(CalendarImportError.UNREACHABLE);
    }
    var deadline = System.nanoTime() + TOTAL_TIMEOUT.toNanos();
    var body = new ByteArrayOutputStream();
    var buffer = new byte[8192];
    try (var content = response.getEntity().getContent()) {
      int read;
      while ((read = content.read(buffer)) != -1) {
        body.write(buffer, 0, read);
        if (body.size() > MAX_BYTES) {
          request.cancel();
          throw new CalendarImportException(CalendarImportError.TOO_LARGE);
        }
        if (System.nanoTime() > deadline) {
          request.cancel();
          throw new CalendarImportException(CalendarImportError.UNREACHABLE);
        }
      }
    }
    return body.toByteArray();
  }

  /** Answers every lookup with the validated addresses, so the name is never resolved again. */
  private record PinnedResolver(InetAddress[] addresses) implements DnsResolver {

    @Override
    public InetAddress[] resolve(String host) {
      return addresses.clone();
    }

    @Override
    public String resolveCanonicalHostname(String host) {
      return host;
    }
  }
}
