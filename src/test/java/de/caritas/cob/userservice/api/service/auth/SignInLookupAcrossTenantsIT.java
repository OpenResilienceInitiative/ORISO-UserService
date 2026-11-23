package de.caritas.cob.userservice.api.service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Magic link and password reset run without a tenant and look across every Träger. Usernames are
 * unique there; an e-mail is not, so it may only pick the one account that carries it.
 */
@DataJpaTest
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=true"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(ConsultantService.class)
class SignInLookupAcrossTenantsIT {

  private static final String SHARED_EMAIL = "shared.person@example.org";

  @Autowired private ConsultantService consultantService;
  @Autowired private AdminRepository adminRepository;
  @Autowired private JdbcTemplate jdbc;

  private final String consultantA = UUID.randomUUID().toString();
  private final String consultantB = UUID.randomUUID().toString();
  private final String adminA = UUID.randomUUID().toString();
  private final String adminB = UUID.randomUUID().toString();

  @BeforeEach
  void seedTheSameEmailInTwoTraeger() {
    insertConsultant(consultantA, "counsellor-a-" + consultantA, 1L);
    insertConsultant(consultantB, "counsellor-b-" + consultantB, 2L);
    insertAdmin(adminA, "admin-a-" + adminA, 1L);
    insertAdmin(adminB, "admin-b-" + adminB, 2L);
  }

  @AfterEach
  void cleanUp() {
    TenantContext.clear();
    jdbc.update("DELETE FROM consultant WHERE consultant_id IN (?, ?)", consultantA, consultantB);
    jdbc.update("DELETE FROM admin WHERE admin_id IN (?, ?)", adminA, adminB);
  }

  @Test
  void consultant_Should_NotResolve_When_TheEmailBelongsToTwoTraeger() {
    assertThat(
            TenantContext.supplyAcrossTenants(
                () -> consultantService.findConsultantForSignIn(SHARED_EMAIL)))
        .isEmpty();
  }

  @Test
  void consultant_Should_Resolve_ByUsername_When_TheEmailIsShared() {
    assertThat(
            TenantContext.supplyAcrossTenants(
                () -> consultantService.findConsultantForSignIn("counsellor-b-" + consultantB)))
        .hasValueSatisfying(consultant -> assertThat(consultant.getId()).isEqualTo(consultantB));
  }

  @Test
  void admin_Should_NotResolve_When_TheEmailBelongsToTwoTraeger() {
    assertThat(TenantContext.supplyAcrossTenants(() -> adminRepository.findForSignIn(SHARED_EMAIL)))
        .isEmpty();
  }

  @Test
  void admin_Should_Resolve_ByUsername_When_TheEmailIsShared() {
    assertThat(
            TenantContext.supplyAcrossTenants(
                () -> adminRepository.findForSignIn("admin-b-" + adminB)))
        .map(Admin::getId)
        .hasValue(adminB);
  }

  private void insertConsultant(String id, String username, long tenantId) {
    jdbc.update(
        "INSERT INTO consultant (consultant_id, username, first_name, last_name, email, tenant_id,"
            + " is_team_consultant, is_absent, language_formal, create_date, update_date,"
            + " walk_through_enabled, notifications_enabled)"
            + " VALUES (?, ?, 'Synthetic', 'Counsellor', ?, ?, 0, 0, 1, NOW(), NOW(), 0, 0)",
        id,
        username,
        SHARED_EMAIL,
        tenantId);
  }

  private void insertAdmin(String id, String username, long tenantId) {
    jdbc.update(
        "INSERT INTO admin (admin_id, username, first_name, last_name, email, type, tenant_id,"
            + " create_date, update_date)"
            + " VALUES (?, ?, 'Synthetic', ?, ?, 'AGENCY', ?, NOW(), NOW())",
        id,
        username,
        id,
        SHARED_EMAIL,
        tenantId);
  }
}
