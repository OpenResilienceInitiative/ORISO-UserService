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
 * about an advice seeker who belongs to both by membership only (ORISO-Admin#1524).
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

  @AfterEach
  void cleanUp() {
    userAgencyRepository.deleteAll(userAgencyRepository.findByUserUserIdIn(created));
    userRepository.deleteAllById(created);
  }

  @Test
  void unlink_Should_letExactlyOneOfTwoConcurrentDeletionsFindTheAdviceSeekerOnlyThere()
      throws Exception {
    var userId = membershipsOnlyAdviceSeeker();
    // Each side waits, after asking whether the advice seeker is linked elsewhere, until the
    // other side asked too. Under the row lock the other side cannot ask before this one
    // committed, so the wait times out; without it both would see the other's membership.
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
        .hasSize(2)
        .satisfiesExactlyInAnyOrder(
            outcome -> assertThat(outcome).isInstanceOf(Kept.class),
            outcome -> assertThat(outcome).isInstanceOf(OnlyHere.class));
    // The side that found them only there kept its membership: until their account is marked,
    // it is how the next call finds them again.
    var onlyThere = atHere instanceof OnlyHere ? AGENCY : OTHER_AGENCY;
    assertThat(userAgencyRepository.findByUserUserIdIn(List.of(userId)))
        .extracting(UserAgency::getAgencyId)
        .containsExactly(onlyThere);
  }

  private AgencyAdviceSeekerUnlinker unlinker(CountDownLatch bothAsked) {
    var memberships = mock(UserAgencyRepository.class, delegatesTo(userAgencyRepository));
    doAnswer(
            call -> {
              boolean elsewhere =
                  userAgencyRepository.hasMembershipOutsideAgency(
                      call.getArgument(0), call.getArgument(1));
              bothAsked.countDown();
              bothAsked.await(1, TimeUnit.SECONDS);
              return elsewhere;
            })
        .when(memberships)
        .hasMembershipOutsideAgency(any(), any());
    return new AgencyAdviceSeekerUnlinker(
        userRepository, sessionRepository, memberships, transactionManager);
  }

  private String membershipsOnlyAdviceSeeker() {
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
    for (long agencyId : new long[] {AGENCY, OTHER_AGENCY}) {
      var membership = new UserAgency();
      membership.setUser(user);
      membership.setAgencyId(agencyId);
      userAgencyRepository.save(membership);
    }
    return id;
  }
}
