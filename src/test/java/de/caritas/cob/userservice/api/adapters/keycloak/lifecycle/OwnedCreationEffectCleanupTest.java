package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnedCreationEffectCleanupTest {
  @Test
  void appointmentCleanupUsesCapturedOwnerAndTenantWithoutLookingUpRolledBackConsultant() {
    var matrix = mock(MatrixSynapseService.class);
    var appointments = mock(AppointmentService.class);
    var effect =
        new JdbcOwnedLifecycleEffects.OwnedEffect(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "owned-account",
            "7",
            UUID.randomUUID(),
            JdbcOwnedLifecycleEffects.Kind.APPOINTMENT_CONSULTANT,
            "owned-account",
            "owned-account",
            "CREATED",
            "ACKNOWLEDGED");
    new OrdinaryRegistrationEffectCleanup(matrix, appointments).clean(effect);
    verify(appointments).deleteOwnedCreationConsultant("owned-account", 7L);
    verifyNoInteractions(matrix);
  }

  @Test
  void uncertainOrForeignAppointmentCannotBeDeleted() {
    var matrix = mock(MatrixSynapseService.class);
    var appointments = mock(AppointmentService.class);
    var cleaner = new OrdinaryRegistrationEffectCleanup(matrix, appointments);
    var effect =
        new JdbcOwnedLifecycleEffects.OwnedEffect(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "owned-account",
            "7",
            UUID.randomUUID(),
            JdbcOwnedLifecycleEffects.Kind.APPOINTMENT_CONSULTANT,
            "owned-account",
            "foreign-account",
            "CREATED",
            "ACKNOWLEDGED");
    assertThrows(IllegalStateException.class, () -> cleaner.clean(effect));
    verifyNoInteractions(matrix, appointments);
  }
}
