package de.caritas.cob.userservice.api.port.out;

public interface IdentityDummyEmailUpdater {

  String updateDummyEmail(String userId, IdentityDummyEmailUpdate update);
}
