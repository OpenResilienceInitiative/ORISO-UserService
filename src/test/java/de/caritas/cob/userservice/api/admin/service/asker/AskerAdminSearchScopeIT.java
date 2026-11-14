package de.caritas.cob.userservice.api.admin.service.asker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.AskerAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AskerFilter;
import de.caritas.cob.userservice.api.adapters.web.dto.AskerSearchResultDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Agencies;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Reach;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Tenant;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserAgency;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.AskerSearchRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The advice-seeker search through the real {@link AdminScope#narrow} on the schema: only {@link
 * AdminScope#current()} is fixed to the reach under test, so a wrong scope callback in the service
 * shows up as wrong rows or totals.
 */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfiguration.class)
class AskerAdminSearchScopeIT {

  private static final long TENANT = 990_401L;
  private static final long CENTRE_A = 990_701L;
  private static final long CENTRE_B = 990_702L;

  @Autowired private AskerSearchRepository askerSearchRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private UserAgencyRepository userAgencyRepository;
  @Autowired private EntityManager entityManager;

  private User inCentreA;
  private User inCentreB;
  private User inBoth;

  @BeforeEach
  void persistAskers() {
    inCentreA = asker();
    inCentreB = asker();
    inBoth = asker();
    session(inCentreA, CENTRE_A);
    membership(inCentreB, CENTRE_B);
    session(inBoth, CENTRE_A);
    membership(inBoth, CENTRE_B);
    entityManager.flush();
    entityManager.clear();
  }

  @Test
  void findAskers_Should_findNobody_When_agencyAdminHasNoCentres() {
    var result = search(new Agencies(TENANT, Set.of()), new AskerFilter());

    assertThat(result.getTotal()).isZero();
    assertThat(result.getEmbedded()).isEmpty();
  }

  @Test
  void findAskers_Should_findNobody_When_requestedCentreIsNotTheAgencyAdminsOwn() {
    // inBoth is in centre A too, but an admin of centre B must not learn that by filtering.
    var result =
        search(
            new Agencies(TENANT, Set.of(CENTRE_B)), new AskerFilter().agencyId(List.of(CENTRE_A)));

    assertThat(result.getTotal()).isZero();
    assertThat(result.getEmbedded()).isEmpty();
  }

  @Test
  void findAskers_Should_keepOnlyTheAgencyAdminsOwnRequestedCentres() {
    var result =
        search(
            new Agencies(TENANT, Set.of(CENTRE_B)),
            new AskerFilter().agencyId(List.of(CENTRE_A, CENTRE_B)));

    assertThat(result.getTotal()).isEqualTo(2);
    assertThat(ids(result)).containsExactlyInAnyOrder(inCentreB.getUserId(), inBoth.getUserId());
    assertThat(result.getEmbedded())
        .allSatisfy(row -> assertThat(row.getAgencyIds()).containsExactly(CENTRE_B));
  }

  @Test
  void findAskers_Should_findOnlyTheAgencyAdminsAdviceSeekersWithOnlyTheirCentres() {
    var result = search(new Agencies(TENANT, Set.of(CENTRE_A)), new AskerFilter());

    assertThat(result.getTotal()).isEqualTo(2);
    assertThat(ids(result)).containsExactlyInAnyOrder(inCentreA.getUserId(), inBoth.getUserId());
    assertThat(result.getEmbedded())
        .allSatisfy(row -> assertThat(row.getAgencyIds()).containsExactly(CENTRE_A));
  }

  @Test
  void findAskers_Should_findTheWholeTenant_When_tenantAdmin() {
    var result = search(new Tenant(TENANT), new AskerFilter().tenantId(990_999L));

    assertThat(result.getTotal()).isEqualTo(3);
    assertThat(
            result.getEmbedded().stream()
                .filter(row -> row.getId().equals(inBoth.getUserId()))
                .findFirst()
                .orElseThrow()
                .getAgencyIds())
        .containsExactly(CENTRE_A, CENTRE_B);
  }

  private AskerSearchResultDTO search(Reach reach, AskerFilter filter) {
    var scope =
        spy(
            new AdminScope(
                mock(AuthenticatedUser.class),
                mock(AdminRepository.class),
                mock(AdminAgencyRepository.class),
                mock(ConsultantRepository.class),
                mock(ConsultantAgencyRepository.class),
                mock(AgencyService.class),
                mock(UserRepository.class),
                sessionRepository,
                userAgencyRepository));
    doReturn(reach).when(scope).current();
    var service =
        new AskerAdminSearchService(
            askerSearchRepository,
            sessionRepository,
            userAgencyRepository,
            scope,
            new UsernameTranscoder());
    return service.findAskers(1, 50, filter);
  }

  private static List<String> ids(AskerSearchResultDTO result) {
    return result.getEmbedded().stream().map(AskerAdminResponseDTO::getId).toList();
  }

  private User asker() {
    var user = new User();
    user.setUserId(UUID.randomUUID().toString());
    user.setUsername("enc." + user.getUserId());
    user.setEmail(user.getUserId() + "@example.org");
    user.setTenantId(TENANT);
    user.setEncourage2fa(false);
    user.setMagicLinkLoginEnabled(false);
    user.setLanguageCode(LanguageCode.de);
    entityManager.persist(user);
    return user;
  }

  private void session(User user, long agencyId) {
    var session = new Session();
    session.setUser(user);
    session.setPostcode("12345");
    session.setAgencyId(agencyId);
    session.setStatus(SessionStatus.NEW);
    session.setTenantId(TENANT);
    session.setLanguageCode(LanguageCode.de);
    session.setIsConsultantDirectlySet(false);
    session.setRegistrationType(Session.RegistrationType.REGISTERED);
    entityManager.persist(session);
  }

  private void membership(User user, long agencyId) {
    var membership = new UserAgency();
    membership.setUser(user);
    membership.setAgencyId(agencyId);
    entityManager.persist(membership);
  }
}
