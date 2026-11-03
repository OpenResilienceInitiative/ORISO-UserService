package de.caritas.cob.userservice.api.config.auth;

/** An explicitly configured native service-account binding. */
public record TaskActorCredentials(
    TaskActor actor, String clientId, String clientSecret, String serviceSubject) {
  @Override
  public String toString() {
    return "TaskActorCredentials[actor=" + actor + ", credentials=redacted]";
  }
}
