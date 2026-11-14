package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Against a local server, reached directly: the validator, which would refuse the loopback address,
 * is not part of this test.
 */
class HttpCalendarImportFetcherTest {

  private static final byte[] CALENDAR =
      "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n".getBytes(StandardCharsets.UTF_8);

  private static final int SLOW_PIECES = 40;
  private static final long SLOW_PIECE_MILLIS = 100;

  private final HttpCalendarImportFetcher fetcher = new HttpCalendarImportFetcher();
  private final AtomicInteger calendarRequests = new AtomicInteger();
  private HttpServer server;
  private List<InetAddress> loopback;

  @BeforeEach
  void startServer() throws Exception {
    loopback = List.of(InetAddress.getLoopbackAddress());
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/calendar.ics",
        exchange -> {
          calendarRequests.incrementAndGet();
          exchange.getResponseHeaders().add("Content-Type", "text/calendar");
          exchange.sendResponseHeaders(200, CALENDAR.length);
          exchange.getResponseBody().write(CALENDAR);
          exchange.close();
        });
    server.createContext(
        "/redirect",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "/calendar.ics");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/missing",
        exchange -> {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    server.createContext(
        "/large",
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          var chunk = new byte[64 * 1024];
          try (var body = exchange.getResponseBody()) {
            for (int sent = 0; sent <= HttpCalendarImportFetcher.MAX_BYTES; sent += chunk.length) {
              body.write(chunk);
            }
          } catch (java.io.IOException clientGaveUp) {
            // Expected: the fetcher stops reading at the limit.
          }
        });
    server.createContext(
        "/missing-then-slow",
        exchange -> {
          exchange.sendResponseHeaders(404, 0);
          try (var body = exchange.getResponseBody()) {
            for (int piece = 0; piece < SLOW_PIECES; piece++) {
              body.write(new byte[1024]);
              body.flush();
              Thread.sleep(SLOW_PIECE_MILLIS);
            }
          } catch (java.io.IOException clientGaveUp) {
            // Expected: the fetcher drops the connection on the error answer.
          } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
          }
        });
    server.createContext(
        "/large-then-slow",
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          var chunk = new byte[64 * 1024];
          try (var body = exchange.getResponseBody()) {
            for (int sent = 0; sent <= HttpCalendarImportFetcher.MAX_BYTES; sent += chunk.length) {
              body.write(chunk);
            }
            // Then a trickle, each piece well inside the socket timeout.
            for (int piece = 0; piece < SLOW_PIECES; piece++) {
              body.write(new byte[1024]);
              body.flush();
              Thread.sleep(SLOW_PIECE_MILLIS);
            }
          } catch (java.io.IOException clientGaveUp) {
            // Expected: the fetcher drops the connection at the limit.
          } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
          }
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void fetch_Should_ReturnTheBody() {
    assertThat(fetcher.fetch(address("localhost", "/calendar.ics"), loopback)).isEqualTo(CALENDAR);
  }

  @Test
  void fetch_Should_ConnectToTheValidatedAddress_NotResolveTheNameAgain() {
    // The name does not resolve at all; only the pinned address can have answered.
    var body =
        fetcher.fetch(address("calendar.does-not-resolve.invalid", "/calendar.ics"), loopback);

    assertThat(body).isEqualTo(CALENDAR);
  }

  @Test
  void fetch_Should_NotFollowARedirect() {
    assertThat(errorOf("/redirect")).isEqualTo(CalendarImportError.UNREACHABLE);
    assertThat(calendarRequests).hasValue(0);
  }

  @Test
  void fetch_Should_RefuseAnErrorAnswer() {
    assertThat(errorOf("/missing")).isEqualTo(CalendarImportError.UNREACHABLE);
  }

  @Test
  void fetch_Should_StopAtTheSizeLimit() {
    assertThat(errorOf("/large")).isEqualTo(CalendarImportError.TOO_LARGE);
  }

  @Test
  void fetch_Should_DropTheConnection_When_ItGivesUp_InsteadOfReadingTheRest() {
    var started = System.nanoTime();

    assertThat(errorOf("/large-then-slow")).isEqualTo(CalendarImportError.TOO_LARGE);

    // Reading the trickle to its end would take SLOW_PIECES x SLOW_PIECE_MILLIS = 4 seconds.
    assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
        .isLessThan(java.time.Duration.ofMillis(SLOW_PIECES * SLOW_PIECE_MILLIS / 2));
  }

  @Test
  void fetch_Should_DropTheConnection_When_AnErrorAnswerKeepsSending() {
    var started = System.nanoTime();

    assertThat(errorOf("/missing-then-slow")).isEqualTo(CalendarImportError.UNREACHABLE);

    assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
        .isLessThan(java.time.Duration.ofMillis(SLOW_PIECES * SLOW_PIECE_MILLIS / 2));
  }

  @Test
  void fetch_Should_ReportAHostThatDoesNotAnswer() {
    var port = server.getAddress().getPort();
    server.stop(0);

    var refusal =
        catchThrowableOfType(
            CalendarImportException.class,
            () ->
                fetcher.fetch(URI.create("http://localhost:" + port + "/calendar.ics"), loopback));

    assertThat(refusal.error()).isEqualTo(CalendarImportError.UNREACHABLE);
  }

  private CalendarImportError errorOf(String path) {
    return catchThrowableOfType(
            CalendarImportException.class,
            () -> fetcher.fetch(address("localhost", path), loopback))
        .error();
  }

  private URI address(String host, String path) {
    return URI.create("http://" + host + ":" + server.getAddress().getPort() + path);
  }
}
