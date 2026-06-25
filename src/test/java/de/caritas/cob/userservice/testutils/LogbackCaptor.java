package de.caritas.cob.userservice.testutils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;

/**
 * Captures Logback log events emitted by a production class's SLF4J logger so tests can assert on
 * logging without mocking a {@code private static final Logger} field (impossible on JDK 17).
 * Replaces the former PowerMock / ReflectionTestUtils logger-injection pattern.
 *
 * <pre>
 *   var captor = LogbackCaptor.forClass(KeycloakService.class);
 *   // exercise code under test
 *   assertThat(captor.contains(Level.WARN, "not found")).isTrue();
 *   captor.detach(); // or use try-with-resources / @AfterEach
 * </pre>
 */
public final class LogbackCaptor implements AutoCloseable {

  private final Logger logger;
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  private LogbackCaptor(final Class<?> clazz) {
    this.logger = (Logger) LoggerFactory.getLogger(clazz);
    this.appender.start();
    this.logger.addAppender(this.appender);
  }

  public static LogbackCaptor forClass(final Class<?> clazz) {
    return new LogbackCaptor(clazz);
  }

  public List<ILoggingEvent> events() {
    return this.appender.list;
  }

  public boolean contains(final Level level, final String messageSubstring) {
    return this.appender.list.stream()
        .anyMatch(
            event ->
                event.getLevel() == level
                    && event.getFormattedMessage().contains(messageSubstring));
  }

  public long count(final Level level) {
    return this.appender.list.stream().filter(event -> event.getLevel() == level).count();
  }

  public List<String> messages(final Level level) {
    return this.appender.list.stream()
        .filter(event -> event.getLevel() == level)
        .map(ILoggingEvent::getFormattedMessage)
        .collect(Collectors.toList());
  }

  public void detach() {
    this.logger.detachAppender(this.appender);
  }

  @Override
  public void close() {
    detach();
  }

  // Convenience aliases (kept so tests written against either naming style compile).
  public static LogbackCaptor attach(final Class<?> clazz) {
    return forClass(clazz);
  }

  public List<ILoggingEvent> getEvents() {
    return events();
  }

  public long countAtLevel(final Level level) {
    return count(level);
  }

  public boolean hasErrorLog() {
    return count(Level.ERROR) > 0;
  }

  public boolean hasWarnLog() {
    return count(Level.WARN) > 0;
  }
}
