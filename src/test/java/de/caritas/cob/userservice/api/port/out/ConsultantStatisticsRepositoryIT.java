package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.model.SessionAppointment.Kind;
import de.caritas.cob.userservice.api.model.SessionAppointment.Status;
import de.caritas.cob.userservice.api.port.out.ConsultantStatisticsRepository.AppointmentKindCount;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;

/**
 * Executes every query of {@link ConsultantStatisticsRepository} against the H2 testing schema. The
 * session queries are checked for SQL syntax and parameter mapping; the appointment queries also
 * for their scoping, because they decide what a counsellor sees of their own work.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ConsultantStatisticsRepositoryIT {

  private static final String CONSULTANT_ID = "consultant-user-id";
  private static final Long TENANT_ID = 1L;
  private static final LocalDateTime FROM = LocalDateTime.of(2026, 6, 1, 0, 0);
  private static final LocalDateTime TO = LocalDateTime.of(2026, 8, 1, 0, 0);

  @Autowired private ConsultantStatisticsRepository underTest;
  @Autowired private EntityManager entityManager;

  @Test
  void allConsultantScopedQueriesShouldExecute() {
    assertThat(underTest.countAssignedSessionsCreatedInPeriod(CONSULTANT_ID, TENANT_ID, FROM, TO))
        .isZero();
    assertThat(underTest.countActiveSessionsInPeriod(CONSULTANT_ID, TENANT_ID, FROM, TO)).isZero();
  }

  /**
   * ORISO-Frontend#2299: appointments of the counsellor's own sessions in the tenant, by kind;
   * cancelled ones separately; another counsellor's, another tenant's and out-of-period ones never.
   */
  @Test
  void appointmentCountsShouldBeScopedToOwnSessionsTenantAndPeriod() {
    var own = entityManager.find(Session.class, 1L);
    own.setTenantId(TENANT_ID);
    var ownConsultantId = own.getConsultant().getId();
    var otherTenant = sessionOfSameConsultantOtherThan(own);
    otherTenant.setTenantId(99L);
    var from = Instant.parse("2026-07-01T00:00:00Z");
    var to = Instant.parse("2026-08-01T00:00:00Z");

    appointment(own, "2026-07-02T10:00:00Z", Status.SCHEDULED, Kind.VIDEO);
    appointment(own, "2026-07-03T10:00:00Z", Status.SCHEDULED, Kind.VIDEO);
    appointment(own, "2026-07-04T10:00:00Z", Status.SCHEDULED, Kind.PHONE);
    appointment(own, "2026-07-05T10:00:00Z", Status.CANCELLED, Kind.CHAT);
    appointment(own, "2026-08-02T10:00:00Z", Status.SCHEDULED, Kind.VIDEO);
    appointment(otherTenant, "2026-07-02T10:00:00Z", Status.SCHEDULED, Kind.VIDEO);
    entityManager.flush();

    var byKind =
        underTest.countScheduledAppointmentsByKindStartingIn(ownConsultantId, TENANT_ID, from, to);

    assertThat(byKind)
        .extracting(AppointmentKindCount::getKind, AppointmentKindCount::getTotal)
        .containsExactlyInAnyOrder(tuple(Kind.VIDEO, 2L), tuple(Kind.PHONE, 1L));
    assertThat(underTest.countCancelledAppointmentsStartingIn(ownConsultantId, TENANT_ID, from, to))
        .isEqualTo(1L);
    assertThat(
            underTest.countScheduledAppointmentsByKindStartingIn(
                "another-consultant", TENANT_ID, from, to))
        .isEmpty();
  }

  private Session sessionOfSameConsultantOtherThan(Session session) {
    return entityManager
        .createQuery(
            "SELECT s FROM Session s WHERE s.consultant.id = :consultantId AND s.id <> :id",
            Session.class)
        .setParameter("consultantId", session.getConsultant().getId())
        .setParameter("id", session.getId())
        .setMaxResults(1)
        .getSingleResult();
  }

  private void appointment(Session session, String start, Status status, Kind kind) {
    var appointment = new SessionAppointment();
    appointment.setSession(session);
    appointment.setStartTime(Instant.parse(start));
    appointment.setEndTime(Instant.parse(start).plusSeconds(3600));
    appointment.setStatus(status);
    appointment.setKind(kind);
    entityManager.persist(appointment);
  }
}
