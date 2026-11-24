package de.caritas.cob.userservice.api.admin.service.agency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.admin.service.agency.AgencyAdviceSeekerUnlinker.Kept;
import de.caritas.cob.userservice.api.admin.service.agency.AgencyAdviceSeekerUnlinker.OnlyHere;
import de.caritas.cob.userservice.api.admin.service.agency.AgencyAdviceSeekerUnlinker.Outcome;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserAgency;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two Beratungsstellen deleted at the same time, each in its own committed transaction, deciding
 * about an advice seeker who belongs to both (ORISO-Admin#1524).
 */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgencyAdviceSeekerUnlinkerConcurrencyIT {

  private static final long TENANT = 990_103L;
  private static final long AGENCY = 990_711L;
  private static final long OTHER_AGENCY = 990_712L;
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 10, 0);

  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private UserAgencyRepository userAgencyRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  private final List<String> created = new java.util.ArrayList<>();
  private final List<Long> createdSessions = new java.util.ArrayList<>();

  @AfterEach
  void cleanUp() {
    sessionRepository.deleteAllById(createdSessions);
    userAgencyRepository.deleteAll(userAgencyRepository.findByUserUserIdIn(created));
    userRepository.deleteAllById(created);
  }

  @Test
  void
      unlink_Should_letExactlyOneOfTwoConcurrentDeletionsFindTheAdviceSeekerOnlyThere_byMemberships()
          throws Exception {
    var userId = adviceSeeker();
    membership(userId, AGENCY);
    membership(userId, OTHER_AGENCY);

    var onlyThere = runConcurrently(userId);

    // The side that found them only there kept its membership: until their account is marked,
    // it is how the next call finds them again.
    assertThat(userAgencyRepository.findByUserUserIdIn(List.of(userId)))
        .extracting(UserAgency::getAgencyId)
        .containsExactly(onlyThere);
  }

  @Test
  void unlink_Should_letExactlyOneOfTwoConcurrentDeletionsFindTheAdviceSeekerOnlyThere_bySessions()
      throws Exception {
    var userId = adviceSeeker();
    var here = session(userId, AGENCY);
    var there = session(userId, OTHER_AGENCY);

    var onlyThere = runConcurrently(userId);

    // The kept side marked its session; the other side's session stays unmarked with the
    // account, which is marked next.
    var unmarked = onlyThere == AGENCY ? here : there;
    var marked = onlyThere == AGENCY ? there : here;
    assertThat(sessionRepository.findById(unmarked).orElseThrow().getDeleteDate()).isNull();
    assertThat(sessionRepository.findById(marked).orElseThrow().getDeleteDate()).isEqualTo(NOW);
  }

  @Test
  void unlink_Should_findTheAdviceSeekerAgain_When_markingTheirAccountFailed() {
    var userId = adviceSeeker();
    membership(userId, AGENCY);
    membership(userId, OTHER_AGENCY);
    var unlinker = unlinker(new CountDownLatch(0));
    assertThat(unlinker.unlink(userId, OTHER_AGENCY, NOW)).isInstanceOf(Kept.class);

    assertThat(unlinker.unlink(userId, AGENCY, NOW)).isInstanceOf(OnlyHere.class);
    // Marking the account failed here, so nothing about the advice seeker changed. The next
    // call lists them again from the committed data and comes to the same decision.
    assertThat(userRepository.findActiveAdviceSeekerIdsLinkedToAgency(AGENCY)).contains(userId);
    assertThat(unlinker.unlink(userId, AGENCY, NOW)).isInstanceOf(OnlyHere.class);
    assertThat(userAgencyRepository.findByUserUserIdIn(List.of(userId)))
        .extracting(UserAgency::getAgencyId)
        .containsExactly(AGENCY);
  }

  /** Both deletions decide at once; returns the agency that found the advice seeker only there. */
  private long runConcurrently(String userId) throws Exception {
    // Each side waits, after asking whether the advice seeker is linked elsewhere, until the
    // other side asked too. Under the row lock the other side cannot ask before this one
    // committed, so the wait times out; without it both would see the other's link.
    var bothAsked = new CountDownLatch(2);
    var here = unlinker(bothAsked);
    var there = unlinker(bothAsked);

    Outcome atHere;
    Outcome atThere;
    try (var workers = Executors.newFixedThreadPool(2)) {
      var a = workers.submit(() -> here.unlink(userId, AGENCY, NOW));
      var b = workers.submit(() -> there.unlink(userId, OTHER_AGENCY, NOW));
      atHere = a.get(20, TimeUnit.SECONDS);
      atThere = b.get(20, TimeUnit.SECONDS);
    }

    assertThat(List.of(atHere, atThere))
        .satisfiesExactlyInAnyOrder(
            outcome -> assertThat(outcome).isInstanceOf(Kept.class),
            outcome -> assertThat(outcome).isInstanceOf(OnlyHere.class));
    return atHere instanceof OnlyHere ? AGENCY : OTHER_AGENCY;
  }

  private AgencyAdviceSeekerUnlinker unlinker(CountDownLatch bothAsked) {
    // The session check is the first question asked under the lock, whatever links there are.
    var sessions = mock(SessionRepository.class, delegatesTo(sessionRepository));
    doAnswer(
            call -> {
              boolean elsewhere =
                  sessionRepository.hasActiveSessionOutsideAgency(
                      call.getArgument(0), call.getArgument(1));
              bothAsked.countDown();
              bothAsked.await(1, TimeUnit.SECONDS);
              return elsewhere;
            })
        .when(sessions)
        .hasActiveSessionOutsideAgency(any(), any());
    return new AgencyAdviceSeekerUnlinker(
        userRepository, sessions, userAgencyRepository, transactionManager);
  }

  private String adviceSeeker() {
    var id = UUID.randomUUID().toString();
    var user = new User();
    user.setUserId(id);
    user.setUsername("enc." + id);
    user.setEmail(id + "@example.org");
    user.setTenantId(TENANT);
    user.setEncourage2fa(false);
    user.setMagicLinkLoginEnabled(false);
    user.setLanguageCode(LanguageCode.de);
    userRepository.save(user);
    created.add(id);
    return id;
  }

  private void membership(String userId, long agencyId) {
    var membership = new UserAgency();
    membership.setUser(userRepository.findById(userId).orElseThrow());
    membership.setAgencyId(agencyId);
    userAgencyRepository.save(membership);
  }

  private Long session(String userId, long agencyId) {
    var session = new Session();
    session.setUser(userRepository.findById(userId).orElseThrow());
    session.setPostcode("12345");
    session.setAgencyId(agencyId);
    session.setStatus(Session.SessionStatus.IN_PROGRESS);
    session.setTenantId(TENANT);
    session.setLanguageCode(LanguageCode.de);
    session.setIsConsultantDirectlySet(false);
    session.setRegistrationType(Session.RegistrationType.REGISTERED);
    var id = sessionRepository.save(session).getId();
    createdSessions.add(id);
    return id;
  }
}
