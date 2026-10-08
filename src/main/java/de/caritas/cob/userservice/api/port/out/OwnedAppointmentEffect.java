package de.caritas.cob.userservice.api.port.out;

/** Original creation-receipt authority; no existing account or update can acquire this observer. */
public interface OwnedAppointmentEffect {
  void started(String consultantId, Long tenantId);

  void created(String consultantId);
}
