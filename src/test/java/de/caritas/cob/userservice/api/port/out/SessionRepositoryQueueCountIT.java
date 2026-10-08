package de.caritas.cob.userservice.api.port.out;

import static com.neovisionaries.i18n.LanguageCode.de;
import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.model.Session.RegistrationType.ANONYMOUS;
import static de.caritas.cob.userservice.api.model.Session.RegistrationType.REGISTERED;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_ID_OFFENDER;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.testConfig.ConsultingTypeManagerTestConfig;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({ConsultingTypeManagerTestConfig.class})
@Transactional
class SessionRepositoryQueueCountIT {

  @Autowired private SessionRepository sessionRepository;

  @Autowired private UserRepository userRepository;

  @Autowired private Messaging messenger;

  private User user;
  private LocalDateTime referenceCreateDate;

  @BeforeEach
  void setup() {
    user = userRepository.findAll().iterator().next();
    user.setDataPrivacyConfirmation(nowInUtc());
    user = userRepository.save(user);
    referenceCreateDate = nowInUtc();
  }

  /**
   * The window the application actually runs with, asked through the production path.
   *
   * <p>The tests below deliberately do not compute a cutoff of their own. A test that works out
   * {@code now minus the window} the same way the code does agrees with the code by construction,
   * including when both are wrong in the same way — which is exactly how a UTC/local-zone mix
   * survived here unnoticed: the sessions are written in UTC, the cutoff used to be taken from the
   * server's local clock, and the test made the same mistake in the same direction.
   */
  @Test
  void countPendingEnquiriesAheadOf_Should_countOnlyGuestsSeenWithinTheConfiguredWindow() {
    // A guest who walked away: last sign of life well outside the window.
    saveSession(session -> session.setUpdateDate(nowInUtc().minusMinutes(60)));
    // A guest who is still here: the waiting room polled a minute ago.
    saveSession(session -> session.setUpdateDate(nowInUtc().minusMinutes(1)));

    long count =
        messenger.countPendingEnquiriesAheadOf(
            null, CONSULTING_TYPE_ID_OFFENDER, null, referenceCreateDate);

    assertThat(count, is(1L));
  }

  /**
   * Guards the acceptance criterion of ORISO-Frontend#1404 against the fix its own bug report asks
   * for. The report says the asker's own position is counted; it is not — {@code createDate <
   * :beforeDate} is strict, and the entry standing at the asker's own moment is theirs. Anyone who
   * "repairs the off-by-one" by relaxing this to {@code <=} makes the reported symptom real.
   *
   * <p>Whole seconds on purpose: {@code create_date} is a {@code datetime} column, so a reference
   * moment carrying nanoseconds would be cut short on the way into the database and slip under a
   * strict comparison for the wrong reason, leaving the test green whatever the operator says.
   */
  @Test
  void countPendingEnquiriesAheadOf_Should_notCountTheAskersOwnEntry() {
    var ownMoment = nowInUtc().truncatedTo(ChronoUnit.SECONDS);
    saveSession(
        session -> {
          session.setCreateDate(ownMoment);
          session.setUpdateDate(nowInUtc());
        });

    long count =
        messenger.countPendingEnquiriesAheadOf(null, CONSULTING_TYPE_ID_OFFENDER, null, ownMoment);

    assertThat(count, is(0L));
  }

  /**
   * Early live-chat guests were written as REGISTERED sessions with postcode {@code 00000} rather
   * than the ANONYMOUS registration type, and they are still real people in the queue.
   */
  @Test
  void countPendingEnquiriesAheadOf_Should_includeLiveChatStyleRegisteredSessions() {
    saveSession(
        session -> {
          session.setRegistrationType(REGISTERED);
          session.setPostcode("00000");
          session.setCreateDate(referenceCreateDate.minusMinutes(5));
          session.setUpdateDate(nowInUtc());
        });

    long count =
        messenger.countPendingEnquiriesAheadOf(
            null, CONSULTING_TYPE_ID_OFFENDER, null, referenceCreateDate);

    assertThat(count, is(1L));
  }

  private void saveSession(java.util.function.Consumer<Session> customizer) {
    Session session = new Session(user, CONSULTING_TYPE_ID_OFFENDER, "12345", null, NEW, false);
    session.setRegistrationType(ANONYMOUS);
    session.setIsConsultantDirectlySet(false);
    session.setLanguageCode(de);
    session.setCreateDate(referenceCreateDate.minusMinutes(1));
    session.setUpdateDate(nowInUtc());
    customizer.accept(session);
    sessionRepository.save(session);
  }
}
