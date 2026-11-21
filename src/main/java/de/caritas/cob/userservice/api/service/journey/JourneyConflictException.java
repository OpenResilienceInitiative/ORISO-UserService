package de.caritas.cob.userservice.api.service.journey;

/** Someone saved the journey after the writer read it: the writer must reload (HTTP 409). */
public class JourneyConflictException extends RuntimeException {

  private final long currentVersion;

  public JourneyConflictException(long currentVersion) {
    super("The journey was changed meanwhile; current version " + currentVersion);
    this.currentVersion = currentVersion;
  }

  public long getCurrentVersion() {
    return currentVersion;
  }
}
