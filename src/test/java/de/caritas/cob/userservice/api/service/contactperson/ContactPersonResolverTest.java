package de.caritas.cob.userservice.api.service.contactperson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.lenient;

import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.model.AdminAgency.AdminAgencyBase;
import de.caritas.cob.userservice.api.model.AgencyContactPerson;
import de.caritas.cob.userservice.api.model.TenantContactPerson;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.AgencyContactPersonRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.TenantContactPersonRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Which contact person counts for a counselling centre (ORISO-Admin#1534): a stored id counts only
 * while it still names an admin of this centre or its Träger, so a missed cleanup never shows a
 * wrong person (decision 10).
 */
@ExtendWith(MockitoExtension.class)
class ContactPersonResolverTest {

  static final long AGENCY = 7L;
  static final long OTHER_AGENCY = 8L;
  static final long TENANT = 3L;
  static final long OTHER_TENANT = 4L;

  @Mock AdminRepository adminRepository;
  @Mock AdminAgencyRepository adminAgencyRepository;
  @Mock AgencyContactPersonRepository agencyContactPersonRepository;
  @Mock TenantContactPersonRepository tenantContactPersonRepository;
  @Mock ConsultantRepository consultantRepository;
  @Mock ConsultantAgencyRepository consultantAgencyRepository;

  ContactPersonResolver resolver;

  final List<Admin> admins = new ArrayList<>();
  final List<AdminAgencyBase> links = new ArrayList<>();
  final List<AgencyContactPerson> centreRows = new ArrayList<>();
  final List<TenantContactPerson> traegerRows = new ArrayList<>();

  @BeforeEach
  void setUp() {
    resolver =
        new ContactPersonResolver(
            adminRepository,
            adminAgencyRepository,
            agencyContactPersonRepository,
            tenantContactPersonRepository,
            consultantRepository,
            consultantAgencyRepository);
    lenient()
        .when(adminRepository.findAllByIdIn(anySet()))
        .thenAnswer(
            call -> {
              Set<String> ids = call.getArgument(0);
              return admins.stream().filter(a -> ids.contains(a.getId())).toList();
            });
    lenient()
        .when(adminAgencyRepository.findByAdminIdIn(anySet()))
        .thenAnswer(
            call -> {
              Set<String> ids = call.getArgument(0);
              return links.stream().filter(l -> ids.contains(l.getAdminId())).toList();
            });
    lenient()
        .when(agencyContactPersonRepository.findByAgencyIdIn(anyCollection()))
        .thenAnswer(
            call -> {
              Collection<Long> ids = call.getArgument(0);
              return centreRows.stream().filter(r -> ids.contains(r.getAgencyId())).toList();
            });
    lenient()
        .when(tenantContactPersonRepository.findAllById(anyIterable()))
        .thenAnswer(
            call -> {
              Iterable<Long> ids = call.getArgument(0);
              var wanted = new ArrayList<Long>();
              ids.forEach(wanted::add);
              return traegerRows.stream().filter(r -> wanted.contains(r.getTenantId())).toList();
            });
  }

  @Test
  void ownContactAndDeputyCount_When_agencyAdminOfTheCentreAndTraegerAdminOfItsTraeger() {
    agencyAdmin("bst-1", TENANT, AGENCY);
    traegerAdmin("tr-1", TENANT);
    centre(AGENCY, "bst-1", "tr-1");

    var centre = resolver.resolveCentre(AGENCY, TENANT);

    assertThat(centre.source()).isEqualTo(ContactSource.OWN);
    assertThat(centre.own().contact().getId()).isEqualTo("bst-1");
    assertThat(centre.own().deputy().getId()).isEqualTo("tr-1");
    assertThat(centre.effective()).isEqualTo(centre.own());
  }

  @Test
  void ownContactIsUnset_When_theAgencyAdminNoLongerAdministratesTheCentre() {
    agencyAdmin("bst-1", TENANT, OTHER_AGENCY);
    centre(AGENCY, "bst-1", null);

    var centre = resolver.resolveCentre(AGENCY, TENANT);

    assertThat(centre.own().isSet()).isFalse();
    assertThat(centre.source()).isEqualTo(ContactSource.NONE);
  }

  @Test
  void ownContactIsUnset_When_theAdminNoLongerExists() {
    centre(AGENCY, "deleted-admin", null);

    assertThat(resolver.resolveCentre(AGENCY, TENANT).source()).isEqualTo(ContactSource.NONE);
  }

  @Test
  void ownContactIsUnset_When_aTraegerAdminOfAnotherTraeger() {
    traegerAdmin("tr-other", OTHER_TENANT);
    centre(AGENCY, "tr-other", null);

    assertThat(resolver.resolveCentre(AGENCY, TENANT).own().isSet()).isFalse();
  }

  @Test
  void ownContactIsUnset_When_aPlatformAdmin() {
    admin("super-1", AdminType.SUPER, TENANT);
    centre(AGENCY, "super-1", null);

    assertThat(resolver.resolveCentre(AGENCY, TENANT).own().isSet()).isFalse();
  }

  @Test
  void invalidDeputyIsDropped_But_theContactStays() {
    agencyAdmin("bst-1", TENANT, AGENCY);
    agencyAdmin("bst-2", TENANT, OTHER_AGENCY);
    centre(AGENCY, "bst-1", "bst-2");

    var own = resolver.resolveCentre(AGENCY, TENANT).own();

    assertThat(own.contact().getId()).isEqualTo("bst-1");
    assertThat(own.deputy()).isNull();
  }

  @Test
  void deputyEqualToTheContactIsDropped() {
    agencyAdmin("bst-1", TENANT, AGENCY);
    centre(AGENCY, "bst-1", "bst-1");

    assertThat(resolver.resolveCentre(AGENCY, TENANT).own().deputy()).isNull();
  }

  @Test
  void validDeputyWithoutValidContactIsIgnored_And_theTraegerDefaultApplies() {
    agencyAdmin("bst-2", TENANT, AGENCY);
    traegerAdmin("tr-1", TENANT);
    centre(AGENCY, "gone", "bst-2");
    traeger(TENANT, "tr-1", null, null);

    var centre = resolver.resolveCentre(AGENCY, TENANT);

    assertThat(centre.own().isSet()).isFalse();
    assertThat(centre.source()).isEqualTo(ContactSource.TRAEGER_DEFAULT);
    assertThat(centre.effective().contact().getId()).isEqualTo("tr-1");
  }

  @Test
  void traegerDefaultMayBeAnAgencyAdminOfAnyCentreOfTheTraeger() {
    agencyAdmin("bst-9", TENANT, OTHER_AGENCY);
    traegerAdmin("tr-1", TENANT);
    traeger(TENANT, "bst-9", "tr-1", "info@traeger.org");

    var centre = resolver.resolveCentre(AGENCY, TENANT);

    assertThat(centre.source()).isEqualTo(ContactSource.TRAEGER_DEFAULT);
    assertThat(centre.traegerDefault().contact().getId()).isEqualTo("bst-9");
    assertThat(centre.traegerDefault().deputy().getId()).isEqualTo("tr-1");
    assertThat(centre.traegerContactEmail()).isEqualTo("info@traeger.org");
  }

  @Test
  void traegerDefaultIsUnset_When_anAdminOfAnotherTraegerOrAPlatformAdmin() {
    traegerAdmin("tr-other", OTHER_TENANT);
    admin("super-1", AdminType.SUPER, TENANT);
    traeger(TENANT, "tr-other", null, null);
    traeger(OTHER_TENANT, "super-1", null, null);

    assertThat(resolver.resolveCentre(AGENCY, TENANT).source()).isEqualTo(ContactSource.NONE);
    assertThat(resolver.resolveTraeger(OTHER_TENANT).pair().isSet()).isFalse();
  }

  @Test
  void ownContactWinsOverTheTraegerDefault() {
    agencyAdmin("bst-1", TENANT, AGENCY);
    traegerAdmin("tr-1", TENANT);
    centre(AGENCY, "bst-1", null);
    traeger(TENANT, "tr-1", null, null);

    var centre = resolver.resolveCentre(AGENCY, TENANT);

    assertThat(centre.source()).isEqualTo(ContactSource.OWN);
    assertThat(centre.traegerDefault().contact().getId()).isEqualTo("tr-1");
  }

  @Test
  void withoutTraegerNoDefaultIsLookedUp() {
    var centre = resolver.resolveCentre(AGENCY, null);

    assertThat(centre.source()).isEqualTo(ContactSource.NONE);
    assertThat(centre.traegerContactEmail()).isNull();
  }

  @Test
  void resolveCentres_Should_answerEveryRequestedCentreInOneGo() {
    agencyAdmin("bst-1", TENANT, AGENCY);
    traegerAdmin("tr-2", OTHER_TENANT);
    centre(AGENCY, "bst-1", null);
    traeger(OTHER_TENANT, "tr-2", null, null);
    var request = new HashMap<Long, Long>();
    request.put(AGENCY, TENANT);
    request.put(OTHER_AGENCY, OTHER_TENANT);
    request.put(99L, TENANT);

    Map<Long, CentreContact> result = resolver.resolveCentres(request);

    assertThat(result.get(AGENCY).source()).isEqualTo(ContactSource.OWN);
    assertThat(result.get(OTHER_AGENCY).source()).isEqualTo(ContactSource.TRAEGER_DEFAULT);
    assertThat(result.get(99L).source()).isEqualTo(ContactSource.NONE);
  }

  @Test
  void displayEmail_Should_preferTheContactEmailOverTheLoginEmail() {
    var admin = admin("a-1", AdminType.TENANT, TENANT);
    assertThat(ContactPersonResolver.displayEmail(admin)).isEqualTo("a-1@login.example");

    admin.setContactEmail("kontakt@traeger.org");
    assertThat(ContactPersonResolver.displayEmail(admin)).isEqualTo("kontakt@traeger.org");

    admin.setContactEmail("  ");
    assertThat(ContactPersonResolver.displayEmail(admin)).isEqualTo("a-1@login.example");
  }

  @Test
  void isCentreCandidate_and_isTraegerCandidate_followTheValidityRules() {
    var bst = agencyAdmin("bst-1", TENANT, AGENCY);
    var tr = traegerAdmin("tr-1", TENANT);
    var foreign = traegerAdmin("tr-x", OTHER_TENANT);

    assertThat(resolver.isCentreCandidate(bst, AGENCY, TENANT)).isTrue();
    assertThat(resolver.isCentreCandidate(bst, OTHER_AGENCY, TENANT)).isFalse();
    assertThat(resolver.isCentreCandidate(tr, AGENCY, TENANT)).isTrue();
    assertThat(resolver.isCentreCandidate(foreign, AGENCY, TENANT)).isFalse();
    assertThat(resolver.isTraegerCandidate(bst, TENANT)).isTrue();
    assertThat(resolver.isTraegerCandidate(foreign, TENANT)).isFalse();
  }

  @Test
  void displayName_Should_neverShowTheUserName_But_fallBackToTheDisplayEmail() {
    var admin = admin("a-1", AdminType.TENANT, TENANT);
    admin.setFirstName(" ");
    admin.setLastName("");

    assertThat(ContactPersonResolver.displayName(admin)).isEqualTo("a-1@login.example");
    admin.setContactEmail("kontakt@traeger.org");
    assertThat(ContactPersonResolver.displayName(admin)).isEqualTo("kontakt@traeger.org");
  }

  Admin admin(String id, AdminType type, Long tenantId) {
    var admin =
        Admin.builder()
            .id(id)
            .type(type)
            .tenantId(tenantId)
            .username(id)
            .firstName("First " + id)
            .lastName("Last " + id)
            .email(id + "@login.example")
            .build();
    admins.add(admin);
    return admin;
  }

  Admin agencyAdmin(String id, Long tenantId, long agencyId) {
    var admin = admin(id, AdminType.AGENCY, tenantId);
    links.add(link(id, agencyId));
    return admin;
  }

  Admin traegerAdmin(String id, Long tenantId) {
    return admin(id, AdminType.TENANT, tenantId);
  }

  void centre(long agencyId, String contact, String deputy) {
    centreRows.add(
        AgencyContactPerson.builder()
            .agencyId(agencyId)
            .contactAdminId(contact)
            .deputyAdminId(deputy)
            .build());
  }

  void traeger(long tenantId, String contact, String deputy, String email) {
    traegerRows.add(
        TenantContactPerson.builder()
            .tenantId(tenantId)
            .contactAdminId(contact)
            .deputyAdminId(deputy)
            .generalContactEmail(email)
            .build());
  }

  static AdminAgencyBase link(String adminId, long agencyId) {
    return new AdminAgencyBase() {
      @Override
      public Long getId() {
        return 1L;
      }

      @Override
      public Long getAgencyId() {
        return agencyId;
      }

      @Override
      public String getAdminId() {
        return adminId;
      }
    };
  }
}
