package de.caritas.cob.userservice.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The tenant test support behaves like production's tenant model, filter on. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=true"})
@Import(TenantFixtures.class)
@WithTenant(TenantsIT.OWN)
class TenantsIT {

  static final long OWN = 1L;
  static final long FOREIGN = 2L;

  @Autowired private TenantFixtures fixtures;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private AdminRepository adminRepository;
  @MockitoBean private TenantResolverService tenantResolverService;

  @Test
  void aRowSeededInTheActingTraegerIsVisibleToIt() {
    var consultant = fixtures.consultant(OWN, 1L);

    assertThat(consultantRepository.findById(consultant.getId())).isPresent();
  }

  @Test
  void aRowSeededInAnotherTraegerIsHiddenFromTheActingOne() {
    var consultant = fixtures.consultant(FOREIGN, 1L);

    assertThat(consultantRepository.findById(consultant.getId())).isEmpty();
    assertThat(Tenants.acrossAll(() -> consultantRepository.findById(consultant.getId())))
        .isPresent();
  }

  @Test
  void everySeededRowCarriesItsTraeger() {
    var asker = fixtures.adviceSeeker(FOREIGN);
    var session = fixtures.session(asker, 7L, fixtures.consultant(FOREIGN, 7L));
    var admin = fixtures.admin(FOREIGN, AdminType.AGENCY, 7L);

    Tenants.in(
        FOREIGN,
        () -> {
          assertThat(sessionRepository.findById(session.getId()).orElseThrow().getTenantId())
              .isEqualTo(FOREIGN);
          assertThat(adminRepository.findById(admin.getId()).orElseThrow().getTenantId())
              .isEqualTo(FOREIGN);
        });
    assertThat(sessionRepository.findById(session.getId())).isEmpty();
  }

  @Test
  @WithTenant(FOREIGN)
  void aMethodOverridesTheTraegerOfItsClass() {
    assertThat(TenantContext.getCurrentTenant()).isEqualTo(FOREIGN);
  }

  @Test
  @AsTechnicalUser
  void theTechnicalUserSeesEveryTraeger() {
    var own = fixtures.consultant(OWN);
    var foreign = fixtures.consultant(FOREIGN);

    assertThat(consultantRepository.findById(own.getId())).isPresent();
    assertThat(consultantRepository.findById(foreign.getId())).isPresent();
  }

  @Test
  void aRequestResolvesToTheTraegerTheTestActsIn() {
    assertThat(tenantResolverService.resolve(null)).isEqualTo(OWN);

    Tenants.actIn(FOREIGN);

    assertThat(tenantResolverService.resolve(null)).isEqualTo(FOREIGN);
    assertThat(TenantContext.getCurrentTenant()).isEqualTo(FOREIGN);
  }

  @Test
  void workInAnotherTraegerLeavesTheActingOneInPlace() {
    Tenants.in(FOREIGN, () -> fixtures.consultant(FOREIGN));

    assertThat(TenantContext.getCurrentTenant()).isEqualTo(OWN);
  }
}
