package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

  /** A host that sends one byte, then waits this long before each next one. */
  private static final long TRICKLE_MILLIS = 3_000;

  private final HttpCalendarImportFetcher fetcher = new HttpCalendarImportFetcher();
  private final AtomicInteger calendarRequests = new AtomicInteger();
  private HttpServer server;
  private List<InetAddress> loopback;
  private volatile boolean stopping;

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
    server.createContext(
        "/trickle",
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          try (var body = exchange.getResponseBody()) {
            // Each byte comes well inside the socket timeout, so only a deadline ends this.
            for (int piece = 0; piece < 10 && !stopping; piece++) {
              body.write('A');
              body.flush();
              for (long waited = 0; waited < TRICKLE_MILLIS && !stopping; waited += 50) {
                Thread.sleep(50);
              }
            }
          } catch (java.io.IOException clientGaveUp) {
            // Expected: the fetcher drops the connection at its deadline.
          } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
          }
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    stopping = true;
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
    assertThat(Duration.ofNanos(System.nanoTime() - started))
        .isLessThan(Duration.ofMillis(SLOW_PIECES * SLOW_PIECE_MILLIS / 2));
  }

  @Test
  void fetch_Should_DropTheConnection_When_AnErrorAnswerKeepsSending() {
    var started = System.nanoTime();

    assertThat(errorOf("/missing-then-slow")).isEqualTo(CalendarImportError.UNREACHABLE);

    assertThat(Duration.ofNanos(System.nanoTime() - started))
        .isLessThan(Duration.ofMillis(SLOW_PIECES * SLOW_PIECE_MILLIS / 2));
  }

  @Test
  void fetch_Should_CutOffAHostThatTricklesBytes_AtTheDeadline_NotAtTheNextByte() {
    // The socket timeout is far longer than the gap between two bytes, as with a host that sends
    // a byte every 9 seconds against a 10 second socket timeout.
    var strict =
        new HttpCalendarImportFetcher(
            Duration.ofMillis(800), Duration.ofSeconds(10), Duration.ofSeconds(1));
    var started = System.nanoTime();

    var refusal =
        catchThrowableOfType(
            CalendarImportException.class,
            () -> strict.fetch(address("localhost", "/trickle"), loopback));

    assertThat(refusal.error()).isEqualTo(CalendarImportError.UNREACHABLE);
    // Checking only after each read would end it with the second byte, after TRICKLE_MILLIS.
    assertThat(Duration.ofNanos(System.nanoTime() - started))
        .isLessThan(Duration.ofMillis(TRICKLE_MILLIS / 2));
  }

  @Test
  void connectTimeoutPerAddress_Should_ShareTheBudget_SoAllAddressesTogetherStayWithinIt() {
    var budget = HttpCalendarImportFetcher.CONNECT_BUDGET;
    for (int addresses = 1; addresses <= HttpCalendarImportFetcher.MAX_ADDRESSES; addresses++) {
      var each = HttpCalendarImportFetcher.connectTimeoutPerAddress(budget, addresses);

      assertThat(each.toMilliseconds() * addresses)
          .as("%d addresses", addresses)
          .isLessThanOrEqualTo(budget.toMillis());
    }
    assertThat(HttpCalendarImportFetcher.connectTimeoutPerAddress(budget, 1).toMilliseconds())
        .isEqualTo(budget.toMillis());
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
