package de.caritas.cob.userservice.api.picture;

import static org.assertj.core.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ApiResponseEntityExceptionHandler;
import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.CorrelationIdFilter;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import net.logstash.logback.composite.loggingevent.LoggingEventJsonProviders;
import net.logstash.logback.composite.loggingevent.LoggingEventPatternJsonProvider;
import net.logstash.logback.composite.loggingevent.MdcJsonProvider;
import net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;

class PictureDiagnosticsTest {
  private static final String MARKER = "synthetic-private-request-marker";
  private static final Map<String, String> REQUEST_CONTEXT =
      Map.of(
          "CID",
          MARKER,
          "identity",
          "synthetic-private-identity",
          "traceId",
          "synthetic-private-trace",
          "spanId",
          "synthetic-private-span");

  @Test
  void handlerRecordExcludesRequestContextButOtherHandlerDiagnosticsKeepIt() throws Exception {
    try (var capture = new Capture(ApiResponseEntityExceptionHandler.class)) {
      withinRequest(
          () -> {
            var handler = new ApiResponseEntityExceptionHandler();
            var request = new ServletWebRequest(new MockHttpServletRequest());
            // Exercise an existing non-picture diagnostic on the same logger before and after.
            handler.handleCustomBadRequest(
                new BadRequestException("synthetic-other-before"), request);
            for (var error :
                List.of(
                    PictureException.tooLarge(),
                    PictureException.unsupported(),
                    PictureException.invalid(),
                    PictureException.rejected(),
                    PictureException.unavailable())) {
              error.initCause(new IOException("synthetic-private-exception"));
              var response = handler.handlePicture(error, request);
              assertThat(response.getStatusCode()).isEqualTo(error.getStatus());
              assertThat(response.getBody()).isEqualTo(Map.of("reason", error.getMessage()));
              assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
              assertThat(MDC.getCopyOfContextMap()).isEqualTo(REQUEST_CONTEXT);
            }
            handler.handleCustomBadRequest(
                new BadRequestException("synthetic-other-after"), request);
          });
      // Only the 5xx picture failure logs, even at TRACE; all four 4xx failures stay silent.
      assertThat(capture.logs.list).hasSize(3);
      assertUnchangedContext(capture.logs.list.getFirst(), capture);
      assertPrivateRecord(
          capture.logs.list.get(1),
          capture,
          Level.ERROR,
          "Picture request failed: status=503, reason=PICTURE_SCAN_UNAVAILABLE");
      assertThat(capture.logs.list.get(1).getArgumentArray())
          .containsExactly(503, "PICTURE_SCAN_UNAVAILABLE");
      assertUnchangedContext(capture.logs.list.getLast(), capture);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void intakeRecordExcludesRequestContextAndRestoresItBeforeRethrowing(boolean io)
      throws Exception {
    try (var capture = new Capture(PictureIntake.class)) {
      withinRequest(
          () -> {
            capture.logger.info("synthetic-other-before");
            assertThatThrownBy(() -> new PictureIntake().read(failingBody(io), "image/png"))
                .isInstanceOf(PictureException.class)
                .hasMessage("PICTURE_INVALID_IMAGE")
                .hasNoCause();
            assertThat(MDC.getCopyOfContextMap()).isEqualTo(REQUEST_CONTEXT);
            capture.logger.info("synthetic-other-after");
          });
      assertThat(capture.logs.list).hasSize(3);
      assertUnchangedContext(capture.logs.list.getFirst(), capture);
      String category = io ? "IO_FAILURE" : "INVALID_ARGUMENT";
      assertPrivateRecord(
          capture.logs.list.get(1),
          capture,
          Level.DEBUG,
          "Picture intake rejected: category=" + category);
      assertThat(capture.logs.list.get(1).getArgumentArray()).containsExactly(category);
      assertUnchangedContext(capture.logs.list.getLast(), capture);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void loggingFailureRestoresTheEntireRequestContext(boolean intake) throws Exception {
    try (var capture =
        new Capture(intake ? PictureIntake.class : ApiResponseEntityExceptionHandler.class)) {
      var failure = new AssertionError("synthetic logging failure");
      var rejecting =
          new ch.qos.logback.classic.turbo.TurboFilter() {
            @Override
            public ch.qos.logback.core.spi.FilterReply decide(
                org.slf4j.Marker marker,
                Logger logger,
                Level level,
                String format,
                Object[] params,
                Throwable throwable) {
              if (logger == capture.logger) {
                assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
                throw failure;
              }
              return ch.qos.logback.core.spi.FilterReply.NEUTRAL;
            }
          };
      var context = capture.logger.getLoggerContext();
      rejecting.start();
      context.addTurboFilter(rejecting);
      try {
        MDC.setContextMap(REQUEST_CONTEXT);
        assertThatThrownBy(
                () -> {
                  if (intake) new PictureIntake().read(failingBody(true), "image/png");
                  else
                    new ApiResponseEntityExceptionHandler()
                        .handlePicture(
                            PictureException.unavailable(),
                            new ServletWebRequest(new MockHttpServletRequest()));
                })
            .isSameAs(failure);
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(REQUEST_CONTEXT);
      } finally {
        context.getTurboFilterList().remove(rejecting);
        rejecting.stop();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"absent", "empty", "populated"})
  void scopeRestoresSnapshotOnNormalNestedAndExceptionalPaths(String state) {
    var original = MDC.getCopyOfContextMap();
    Map<String, String> expected =
        switch (state) {
          case "absent" -> null;
          case "empty" -> Map.of();
          default -> REQUEST_CONTEXT;
        };
    try {
      if (expected == null) MDC.clear();
      else MDC.setContextMap(expected);
      for (boolean fail : new boolean[] {false, true}) {
        var failure = new AssertionError("synthetic diagnostic failure");
        Runnable diagnostic =
            () ->
                PictureDiagnostics.withoutRequestContext(
                    () -> {
                      assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
                      MDC.put("scope-local", "synthetic-local");
                      PictureDiagnostics.withoutRequestContext(
                          () -> {
                            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
                            MDC.put("nested-local", "synthetic-nested");
                          });
                      assertThat(MDC.getCopyOfContextMap())
                          .containsExactlyEntriesOf(Map.of("scope-local", "synthetic-local"));
                      if (fail) throw failure;
                    });
        if (fail) assertThatThrownBy(diagnostic::run).isSameAs(failure);
        else diagnostic.run();
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(expected);
      }
    } finally {
      if (original == null) MDC.clear();
      else MDC.setContextMap(original);
    }
  }

  private static InputStream failingBody(boolean io) {
    return new InputStream() {
      @Override
      public int read() throws IOException {
        if (io) throw new IOException("synthetic-private-exception");
        throw new IllegalArgumentException("synthetic-private-exception");
      }
    };
  }

  private static void withinRequest(Runnable diagnostic) throws Exception {
    var request = new MockHttpServletRequest("PUT", "/useradmin/consultants/synthetic/picture");
    request.addHeader("X-Correlation-ID", MARKER);
    new CorrelationIdFilter()
        .doFilter(
            request,
            new MockHttpServletResponse(),
            (req, res) -> {
              assertThat(MDC.get("CID")).isEqualTo(MARKER);
              REQUEST_CONTEXT.forEach(MDC::put);
              diagnostic.run();
              assertThat(MDC.getCopyOfContextMap()).isEqualTo(REQUEST_CONTEXT);
            });
    // The existing filter still owns request cleanup.
    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }

  private static void assertPrivateRecord(
      ILoggingEvent event, Capture capture, Level level, String message) {
    assertThat(event.getLevel()).isEqualTo(level);
    assertThat(event.getFormattedMessage()).isEqualTo(message);
    assertThat(event.getThrowableProxy()).isNull();
    String rendered = capture.render(event);
    assertThat(rendered).contains(message).doesNotContain("synthetic-private-");
    assertThat(event.getMDCPropertyMap()).isEmpty();
    System.out.println("PICTURE_DIAGNOSTIC_RECORD " + rendered.trim());
  }

  private static void assertUnchangedContext(ILoggingEvent event, Capture capture) {
    assertThat(event.getMDCPropertyMap()).isEqualTo(REQUEST_CONTEXT);
    assertThat(capture.render(event)).contains(REQUEST_CONTEXT.values().toArray(String[]::new));
  }

  private static final class SnapshotAppender extends ListAppender<ILoggingEvent> {
    @Override
    protected void append(ILoggingEvent event) {
      // Production/async appenders snapshot before the request context is restored or cleared.
      event.prepareForDeferredProcessing();
      super.append(event);
    }
  }

  private static final class Capture implements AutoCloseable {
    final Logger logger;
    final SnapshotAppender logs = new SnapshotAppender();
    final Level previousLevel;
    final boolean previousAdditive;
    final Map<String, String> previousMdc = MDC.getCopyOfContextMap();
    final LoggingEventCompositeJsonEncoder encoder = new LoggingEventCompositeJsonEncoder();

    Capture(Class<?> source) throws Exception {
      logger = (Logger) LoggerFactory.getLogger(source);
      previousLevel = logger.getLevel();
      previousAdditive = logger.isAdditive();
      // Same providers and exact non-testing pattern as the retained independent review probe.
      try (var config = PictureDiagnosticsTest.class.getResourceAsStream("/logback-spring.xml")) {
        var xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(config);
        var configuredProviders =
            (org.w3c.dom.Element) xml.getElementsByTagName("providers").item(0);
        var providers = new LoggingEventJsonProviders();
        providers.addMdc(new MdcJsonProvider());
        var pattern = new LoggingEventPatternJsonProvider();
        pattern.setPattern(
            configuredProviders.getElementsByTagName("pattern").item(0).getTextContent().trim());
        providers.addPattern(pattern);
        encoder.setContext(logger.getLoggerContext());
        encoder.setProviders(providers);
        encoder.start();
      }
      logs.start();
      logger.addAppender(logs);
      logger.setLevel(Level.TRACE);
      logger.setAdditive(false);
    }

    String render(ILoggingEvent event) {
      return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
      logger.detachAppender(logs);
      logs.stop();
      encoder.stop();
      logger.setLevel(previousLevel);
      logger.setAdditive(previousAdditive);
      if (previousMdc == null) MDC.clear();
      else MDC.setContextMap(previousMdc);
    }
  }
}
