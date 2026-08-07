package de.caritas.cob.userservice.api.port.out;

/** Replaces a blank identity email without exposing provider or web-layer types. */
public interface IdentityDummyEmailUpdater {

  String updateDummyEmail(String userId, IdentityDummyEmailUpdate update);
}
