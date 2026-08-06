package de.caritas.cob.userservice.api.service.matrixrtc;

public record CallMediaPolicy(boolean audioAllowed, boolean videoAllowed) {

  public static CallMediaPolicy denied() {
    return new CallMediaPolicy(false, false);
  }
}
