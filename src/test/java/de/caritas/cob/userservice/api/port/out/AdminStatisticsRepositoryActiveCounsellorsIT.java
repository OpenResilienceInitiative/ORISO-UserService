package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;

/**
 * The small-cell minimum counts people who can be behind a figure, not registered accounts
 * (UserService#1560). Runs the counsellor counts against real rows in the H2 testing schema.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class AdminStatisticsRepositoryActiveCounsellorsIT {

  private static final long TENANT_ID = 7701L;
  private static final long AGENCY_ID = 8801L;
  private static final long OTHER_AGENCY_ID = 8802L;

  private long nextRelationId = 990000L;

  @Autowired private AdminStatisticsRepository underTest;
  @Autowired private EntityManager entityManager;

  @Test
  void countsOnlyActiveCounsellors_When_SixAreRegisteredAndOneIsActive() {
    insertConsultant("active", "CREATED", false, false, null, "ACTIVE", AGENCY_ID);
    insertConsultant("absent", "CREATED", true, false, null, "ACTIVE", AGENCY_ID);
    insertConsultant("paused", "CREATED", false, true, null, "ACTIVE", AGENCY_ID);
    insertConsultant(
        "deleted", "CREATED", false, false, "2026-10-01 00:00:00", "ACTIVE", AGENCY_ID);
    insertConsultant("not-created", "IN_PROGRESS", false, false, null, "ACTIVE", AGENCY_ID);
    insertConsultant("in-deletion", "CREATED", false, false, null, "PENDING_DELETION", AGENCY_ID);

    assertThat(underTest.countConsultantsForTenant(TENANT_ID)).isEqualTo(1);
    assertThat(counts(underTest.countConsultantsByAgency(TENANT_ID)))
        .containsExactly(AGENCY_ID + ":1");
    assertThat(counts(underTest.countConsultantsByTenant())).contains(TENANT_ID + ":1");
  }

  @Test
  void countsAConsultantOnce_When_TheyBelongToTwoAgencies() {
    insertConsultant("shared", "CREATED", false, false, null, null, AGENCY_ID, OTHER_AGENCY_ID);

    assertThat(underTest.countConsultantsForTenant(TENANT_ID)).isEqualTo(1);
    assertThat(counts(underTest.countConsultantsByAgency(TENANT_ID)))
        .containsExactlyInAnyOrder(AGENCY_ID + ":1", OTHER_AGENCY_ID + ":1");
  }

  @Test
  void theTenantTotalCountsTheSamePeopleAsTheRows_NotConsultantsWithoutAnAgency() {
    insertConsultant("linked", "CREATED", false, false, null, "ACTIVE", AGENCY_ID);
    insertConsultant("unlinked-1", "CREATED", false, false, null, "ACTIVE");
    insertConsultant("unlinked-2", "CREATED", false, false, null, "ACTIVE");

    assertThat(underTest.countConsultantsForTenant(TENANT_ID)).isEqualTo(1);
    assertThat(counts(underTest.countConsultantsByAgency(TENANT_ID)))
        .containsExactly(AGENCY_ID + ":1");
    assertThat(counts(underTest.countConsultantsByTenant())).contains(TENANT_ID + ":1");
  }

  @Test
  void aLinkThatIsNotFinishedYet_Should_NotCount_InTheRowNorInTheTotal() {
    insertConsultant("linked", "CREATED", false, false, null, "ACTIVE", AGENCY_ID);
    insertConsultant("joining", "CREATED", false, false, null, "ACTIVE");
    insertLink("joining", OTHER_AGENCY_ID, "IN_PROGRESS");

    assertThat(underTest.countConsultantsForTenant(TENANT_ID)).isEqualTo(1);
    assertThat(counts(underTest.countConsultantsByAgency(TENANT_ID)))
        .containsExactly(AGENCY_ID + ":1");
  }

  private static List<String> counts(
      List<AdminStatisticsRepository.GroupCountProjection> projections) {
    return projections.stream()
        .map(projection -> projection.getGroupId() + ":" + projection.getTotal())
        .toList();
  }

  private void insertConsultant(
      String name,
      String status,
      boolean absent,
      boolean managedPaused,
      String deleteDate,
      String lifecycleState,
      Long... agencyIds) {
    var consultantId = "stats-1560-" + name;
    entityManager
        .createNativeQuery(
            "INSERT INTO consultant (consultant_id, username, first_name, last_name, email, "
                + "is_absent, is_team_consultant, language_formal, encourage_2fa, "
                + "two_factor_required, live_chat_via_sidebar, walk_through_enabled, "
                + "notifications_enabled, managed_paused, status, delete_date, "
                + "deletion_lifecycle_state, tenant_id) "
                + "VALUES (:id, :id, 'A', 'B', :email, :absent, 0, 1, 1, 0, 0, 0, 1, :paused, "
                + ":status, :deleteDate, :lifecycle, :tenant)")
        .setParameter("id", consultantId)
        .setParameter("email", consultantId + "@example.org")
        .setParameter("absent", absent ? 1 : 0)
        .setParameter("paused", managedPaused)
        .setParameter("status", status)
        .setParameter("deleteDate", deleteDate == null ? null : Timestamp.valueOf(deleteDate))
        .setParameter("lifecycle", lifecycleState)
        .setParameter("tenant", TENANT_ID)
        .executeUpdate();
    for (var agencyId : agencyIds) {
      insertLink(consultantId, agencyId, "CREATED");
    }
    entityManager.flush();
  }

  private void insertLink(String consultantIdOrName, long agencyId, String status) {
    var consultantId =
        consultantIdOrName.startsWith("stats-1560-")
            ? consultantIdOrName
            : "stats-1560-" + consultantIdOrName;
    entityManager
        .createNativeQuery(
            "INSERT INTO consultant_agency (id, consultant_id, agency_id, tenant_id, status) "
                + "VALUES (:relationId, :id, :agency, :tenant, :status)")
        .setParameter("relationId", nextRelationId++)
        .setParameter("id", consultantId)
        .setParameter("agency", agencyId)
        .setParameter("tenant", TENANT_ID)
        .setParameter("status", status)
        .executeUpdate();
    entityManager.flush();
  }
}
