package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;

/**
 * Exact recorded effects for the registration path; all its local writes share the saga DB
 * transaction.
 */
public final class OrdinaryRegistrationEffectCleanup
    implements JdbcOwnedLifecycleEffects.CleanupOperations {
  private final MatrixSynapseService matrix;
  private final de.caritas.cob.userservice.api.service.appointment.AppointmentService appointments;

  public OrdinaryRegistrationEffectCleanup(MatrixSynapseService matrix) {
    this(matrix, null);
  }

  public OrdinaryRegistrationEffectCleanup(
      MatrixSynapseService matrix,
      de.caritas.cob.userservice.api.service.appointment.AppointmentService appointments) {
    this.matrix = matrix;
    this.appointments = appointments;
  }

  @Override
  public void clean(JdbcOwnedLifecycleEffects.OwnedEffect effect) {
    if (!"ACKNOWLEDGED".equals(effect.state())
        || !"CREATED".equals(effect.provenance())
        || effect.targetId() == null
        || effect.targetId().isBlank()) throw incomplete();
    boolean done =
        switch (effect.kind()) {
          case MATRIX_USER -> {
            if (!effect.targetId().equals(effect.requestedTarget())
                || !effect.targetId().startsWith("@")) throw incomplete();
            yield matrix.deactivateUser(effect.targetId());
          }
          case PRIVATE_ROOM -> {
            if (!effect.targetId().startsWith("!")) throw incomplete();
            yield matrix.purgeRoom(effect.targetId());
          }
          case APPOINTMENT_CONSULTANT -> {
            if (appointments == null
                || !effect.targetId().equals(effect.accountId())
                || !effect.targetId().equals(effect.requestedTarget())
                || effect.tenantId() == null
                || !effect.tenantId().matches("[1-9][0-9]{0,18}")) throw incomplete();
            appointments.deleteOwnedCreationConsultant(
                effect.targetId(), Long.valueOf(effect.tenantId()));
            yield true;
          }
          default -> throw incomplete();
        };
    if (!done) throw incomplete();
  }

  private static IllegalStateException incomplete() {
    return new IllegalStateException("Owned registration cleanup remains pending");
  }
}
