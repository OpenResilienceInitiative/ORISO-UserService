package de.caritas.cob.userservice.api.admin.service.asker;

import static org.assertj.core.api.Assertions.assertThat;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserAgency;
import de.caritas.cob.userservice.api.port.out.AskerSearchRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

/** Runs the advice-seeker search against the schema, on rows of a Träger no seed data uses. */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfiguration.class)
class AskerAdminSpecificationIT {

  private static final long TENANT = 990_001L;
  private static final long OTHER_TENANT = 990_002L;
  private static final long SESSION_AGENCY = 990_501L;
  private static final long MEMBERSHIP_AGENCY = 990_502L;
  private static final long FOREIGN_AGENCY = 990_503L;

  @Autowired private AskerSearchRepository askerSearchRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private UserAgencyRepository userAgencyRepository;
  @Autowired private EntityManager entityManager;

  private User withSession;
  private User withMembership;
  private User withoutAgency;
  private User otherTenant;

  @BeforeEach
  void persistAskers() {
    withSession = asker("enc.session-asker", TENANT);
    withMembership = asker("plain-member", TENANT);
    withoutAgency = asker("enc.no-agency", TENANT);
    otherTenant = asker("enc.other-tenant", OTHER_TENANT);
    session(withSession, SESSION_AGENCY);
    session(otherTenant, SESSION_AGENCY);
    membership(withMembership, MEMBERSHIP_AGENCY);
    session(withoutAgency, null);
    entityManager.flush();
    entityManager.clear();
  }

  @Test
  void matching_Should_findAskersOfAgencyThroughSessionOrMembership() {
    assertThat(ids(AskerAdminSpecification.matching(Set.of(), SESSION_AGENCY, TENANT)))
        .containsExactly(withSession.getUserId());
    assertThat(ids(AskerAdminSpecification.matching(Set.of(), MEMBERSHIP_AGENCY, TENANT)))
        .containsExactly(withMembership.getUserId());
    assertThat(ids(AskerAdminSpecification.matching(Set.of(), FOREIGN_AGENCY, TENANT))).isEmpty();
  }

  @Test
  void matching_Should_keepTenantsApart() {
    assertThat(ids(AskerAdminSpecification.matching(Set.of(), null, TENANT)))
        .containsExactlyInAnyOrder(
            withSession.getUserId(), withMembership.getUserId(), withoutAgency.getUserId());
    assertThat(ids(AskerAdminSpecification.matching(Set.of(), SESSION_AGENCY, OTHER_TENANT)))
        .containsExactly(otherTenant.getUserId());
  }

  @Test
  void matching_Should_matchAnyStoredFormOfTheUsername() {
    assertThat(
            ids(
                AskerAdminSpecification.matching(
                    Set.of("plain-member", "enc.plain-member"), null, TENANT)))
        .containsExactly(withMembership.getUserId());
  }

  @Test
  void inAgencies_Should_narrowToTheAgencyAdminsAgencies() {
    Specification<User> narrowed =
        Specification.where(AskerAdminSpecification.matching(Set.of(), null, TENANT))
            .and(
                (root, query, cb) ->
                    AskerAdminSpecification.inAgencies(
                        root, query, cb, Set.of(SESSION_AGENCY, MEMBERSHIP_AGENCY)));

    assertThat(ids(narrowed))
        .containsExactlyInAnyOrder(withSession.getUserId(), withMembership.getUserId());
  }

  @Test
  void agencyLookups_Should_returnSessionAgenciesAndMembershipsOfThePage() {
    var userIds = List.of(withSession.getUserId(), withMembership.getUserId());

    assertThat(sessionRepository.findAgencyIdsByUserIds(userIds))
        .extracting(row -> row.getUserId() + ":" + row.getAgencyId())
        .containsExactly(withSession.getUserId() + ":" + SESSION_AGENCY);
    assertThat(userAgencyRepository.findByUserUserIdIn(userIds))
        .extracting(UserAgency::getAgencyId)
        .containsExactly(MEMBERSHIP_AGENCY);
  }

  private List<String> ids(Specification<User> specification) {
    return askerSearchRepository
        .findAll(specification, PageRequest.of(0, 50, Sort.by("userId")))
        .map(User::getUserId)
        .getContent();
  }

  private User asker(String username, long tenantId) {
    var user = new User();
    user.setUserId(UUID.randomUUID().toString());
    user.setUsername(username);
    user.setEmail(user.getUserId() + "@example.org");
    user.setTenantId(tenantId);
    user.setEncourage2fa(false);
    user.setMagicLinkLoginEnabled(false);
    user.setLanguageCode(LanguageCode.de);
    entityManager.persist(user);
    return user;
  }

  private void session(User user, Long agencyId) {
    var session = new Session();
    session.setUser(user);
    session.setPostcode("12345");
    session.setAgencyId(agencyId);
    session.setStatus(SessionStatus.NEW);
    session.setTenantId(user.getTenantId());
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
