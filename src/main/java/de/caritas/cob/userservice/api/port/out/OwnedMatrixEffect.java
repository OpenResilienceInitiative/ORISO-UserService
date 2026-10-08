package de.caritas.cob.userservice.api.port.out;

/** Durable observation of a remote effect belonging to an unfinished creation receipt. */
public interface OwnedMatrixEffect {
  void started(String requestedTarget);

  void created(String exactTarget);

  void restoreDeactivated(String exactTarget);

  void rejectedWithoutEffect();
}
