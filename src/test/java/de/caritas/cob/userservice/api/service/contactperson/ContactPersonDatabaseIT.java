package de.caritas.cob.userservice.api.service.contactperson;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.model.AgencyContactPerson;
import de.caritas.cob.userservice.api.model.TenantContactPerson;
import de.caritas.cob.userservice.api.port.out.AgencyContactPersonRepository;
import de.caritas.cob.userservice.api.port.out.TenantContactPersonRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The contact-person queries against the schema (ORISO-Admin#1534): resolution with the real admin,
 * admin_agency and contact rows, and the cleanup when an admin is deleted.
 */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  JpaAuditingConfiguration.class,
  ContactPersonResolver.class,
  ContactPersonReferences.class
})
class ContactPersonDatabaseIT {

  static final long TENANT = 991_534L;
  static final long AGENCY = 991_501L;
  static final long OTHER_AGENCY = 991_502L;

  @Autowired ContactPersonResolver resolver;
  @Autowired ContactPersonReferences references;
  @Autowired AgencyContactPersonRepository agencyContactPersonRepository;
  @Autowired TenantContactPersonRepository tenantContactPersonRepository;
  @Autowired EntityManager entityManager;

  @BeforeEach
  void persist() {
    var bst = admin("cp-bst-1", AdminType.AGENCY);
    bst.setContactEmail("kontakt@bst.example");
    admin("cp-tr-1", AdminType.TENANT);
    var other = admin("cp-bst-2", AdminType.AGENCY);
    link(bst, AGENCY);
    link(other, OTHER_AGENCY);
    agencyContactPersonRepository.save(
        AgencyContactPerson.builder()
            .agencyId(AGENCY)
            .tenantId(TENANT)
            .contactAdminId("cp-bst-1")
            .deputyAdminId("cp-tr-1")
            .build());
    agencyContactPersonRepository.save(
        AgencyContactPerson.builder()
            .agencyId(OTHER_AGENCY)
            .tenantId(TENANT)
            .contactAdminId("cp-bst-1")
            .build());
    tenantContactPersonRepository.save(
        TenantContactPerson.builder()
            .tenantId(TENANT)
            .contactAdminId("cp-tr-1")
            .deputyAdminId("cp-bst-2")
            .generalContactEmail("info@traeger.example")
            .build());
    entityManager.flush();
    entityManager.clear();
  }

  @Test
  void resolveCentre_Should_validateAgainstTheStoredAdminsAndRelations() {
    var centre = resolver.resolveCentre(AGENCY, TENANT);

    assertThat(centre.source()).isEqualTo(ContactSource.OWN);
    assertThat(centre.own().contact().getId()).isEqualTo("cp-bst-1");
    assertThat(ContactPersonResolver.displayEmail(centre.own().contact()))
        .isEqualTo("kontakt@bst.example");
    assertThat(centre.own().deputy().getId()).isEqualTo("cp-tr-1");
    assertThat(centre.traegerDefault().contact().getId()).isEqualTo("cp-tr-1");
    assertThat(centre.traegerDefault().deputy().getId()).isEqualTo("cp-bst-2");
    assertThat(centre.traegerContactEmail()).isEqualTo("info@traeger.example");

    // cp-bst-1 is no agency admin of the other centre: the Träger default applies there.
    assertThat(resolver.resolveCentre(OTHER_AGENCY, TENANT).source())
        .isEqualTo(ContactSource.TRAEGER_DEFAULT);
    assertThat(resolver.centreCandidates(AGENCY, TENANT))
        .extracting(Admin::getId)
        .containsExactlyInAnyOrder("cp-bst-1", "cp-tr-1");
    assertThat(resolver.traegerCandidates(TENANT))
        .extracting(Admin::getId)
        .containsExactlyInAnyOrder("cp-bst-1", "cp-bst-2", "cp-tr-1");
  }

  @Test
  void forgetAdmin_Should_dropTheDeputy_And_takeTheDeputyAlongWithTheContact() {
    references.forgetAdmin("cp-tr-1");
    entityManager.clear();

    assertThat(agencyContactPersonRepository.findById(AGENCY).orElseThrow().getDeputyAdminId())
        .isNull();
    var traeger = tenantContactPersonRepository.findById(TENANT).orElseThrow();
    assertThat(traeger.getContactAdminId()).isNull();
    assertThat(traeger.getDeputyAdminId()).as("a deputy needs a contact person").isNull();
    assertThat(traeger.getGeneralContactEmail()).isEqualTo("info@traeger.example");

    references.forgetAdmin("cp-bst-1");
    entityManager.clear();

    assertThat(agencyContactPersonRepository.findById(AGENCY)).isEmpty();
    assertThat(agencyContactPersonRepository.findById(OTHER_AGENCY)).isEmpty();
    assertThat(tenantContactPersonRepository.findById(TENANT)).isPresent();
  }

  Admin admin(String id, AdminType type) {
    var admin =
        Admin.builder()
            .id(id)
            .type(type)
            .tenantId(TENANT)
            .username(id)
            .firstName("Vor")
            .lastName(id)
            .email(id + "@login.example")
            .createDate(LocalDateTime.now())
            .updateDate(LocalDateTime.now())
            .build();
    entityManager.persist(admin);
    return admin;
  }

  void link(Admin admin, long agencyId) {
    entityManager.persist(AdminAgency.builder().admin(admin).agencyId(agencyId).build());
  }
}
