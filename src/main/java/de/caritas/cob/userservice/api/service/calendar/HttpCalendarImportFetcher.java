package de.caritas.cob.userservice.api.service.calendar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Reads a calendar over https (ADR-032, ORISO-UserService#1645). The connection goes to the
 * addresses the validator approved, whatever the name resolves to by now, while TLS still checks
 * the certificate against the host name. No redirect is followed (it would lead to an address
 * nobody checked), nothing is retried, and time and size are bounded: connecting shares one budget
 * across the host's addresses, and the whole request is cut off at its deadline wherever it waits,
 * so a host that sends a byte now and then cannot keep it open past the deadline.
 */
@Component
public class HttpCalendarImportFetcher implements CalendarImportFetcher {

  static final int MAX_BYTES = 2 * 1024 * 1024;

  /** How many of the host's addresses are tried; they share the connect budget. */
  static final int MAX_ADDRESSES = 4;

  static final Duration CONNECT_BUDGET = Duration.ofSeconds(10);
  private static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(30);

  /** Cancels a request at its deadline: connecting, in the TLS handshake or reading the body. */
  private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();

  private final Duration totalTimeout;
  private final Duration socketTimeout;
  private final Duration connectBudget;

  @Autowired
  public HttpCalendarImportFetcher() {
    this(TOTAL_TIMEOUT, SOCKET_TIMEOUT, CONNECT_BUDGET);
  }

  HttpCalendarImportFetcher(Duration totalTimeout, Duration socketTimeout, Duration connectBudget) {
    this.totalTimeout = totalTimeout;
    this.socketTimeout = socketTimeout;
    this.connectBudget = connectBudget;
  }

  @Override
  public byte[] fetch(URI address, List<InetAddress> validated) {
    var deadline = System.nanoTime() + totalTimeout.toNanos();
    var pinned = validated.stream().limit(MAX_ADDRESSES).toArray(InetAddress[]::new);
    var timeout = Timeout.of(socketTimeout);
    var connections =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(new PinnedResolver(pinned))
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(connectTimeoutPerAddress(connectBudget, pinned.length))
                    .setSocketTimeout(timeout)
                    .build())
            .build();
    var request = new HttpGet(address);
    request.setHeader(HttpHeaders.ACCEPT, "text/calendar, */*;q=0.1");
    request.setHeader(HttpHeaders.USER_AGENT, "ORISO-Calendar-Import");
    var cutOff =
        DEADLINES.schedule(
            () -> {
              request.cancel();
            },
            totalTimeout.toNanos(),
            TimeUnit.NANOSECONDS);
    try (var client =
        HttpClients.custom()
            .setConnectionManager(connections)
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .disableCookieManagement()
            .disableAuthCaching()
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setConnectionRequestTimeout(timeout)
                    .setResponseTimeout(timeout)
                    .setRedirectsEnabled(false)
                    .build())
            .build()) {
      return client.execute(request, response -> bodyOf(request, response, deadline));
    } catch (IOException unreachable) {
      // Also a request cancelled at its deadline.
      throw new CalendarImportException(CalendarImportError.UNREACHABLE);
    } finally {
      cutOff.cancel(false);
    }
  }

  /**
   * Each address gets its share of the budget, so a host whose addresses all stay silent costs the
   * budget once, not once per address.
   */
  static Timeout connectTimeoutPerAddress(Duration budget, int addresses) {
    return Timeout.ofMilliseconds(Math.max(1, budget.toMillis() / Math.max(1, addresses)));
  }

  /**
   * Only a plain 200 carries a calendar; a redirect is refused like any other answer. Giving up on
   * a body cancels the request first: closing the stream would otherwise read the rest of it, with
   * no deadline, before the connection is let go.
   *
   * @param deadline in {@link System#nanoTime()}; checked after every read, while a read that waits
   *     is ended by the cancellation at the deadline
   */
  static byte[] bodyOf(Cancellable request, ClassicHttpResponse response, long deadline)
      throws IOException {
    if (response.getCode() != 200 || response.getEntity() == null) {
      request.cancel();
      throw new CalendarImportException(CalendarImportError.UNREACHABLE);
    }
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
        if (System.nanoTime() - deadline > 0) {
          request.cancel();
          throw new CalendarImportException(CalendarImportError.UNREACHABLE);
        }
      }
    }
    return body.toByteArray();
  }

  private static ScheduledThreadPoolExecutor deadlines() {
    var executor =
        new ScheduledThreadPoolExecutor(
            1,
            task -> {
              var thread = new Thread(task, "calendar-import-deadline");
              thread.setDaemon(true);
              return thread;
            });
    executor.setRemoveOnCancelPolicy(true);
    return executor;
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
