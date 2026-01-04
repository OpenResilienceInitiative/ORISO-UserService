package de.caritas.cob.userservice.api.testHelper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.ArrayList;
import java.util.List;

public class TestLogAppender extends AppenderBase<ILoggingEvent> {
  private final List<ILoggingEvent> events = new ArrayList<>();

  @Override
  protected void append(ILoggingEvent eventObject) {
    events.add(eventObject);
  }

  public boolean contains(String message, Level level) {
    return events.stream()
        .anyMatch(event -> event.getMessage().contains(message) && event.getLevel().equals(level));
  }
}
