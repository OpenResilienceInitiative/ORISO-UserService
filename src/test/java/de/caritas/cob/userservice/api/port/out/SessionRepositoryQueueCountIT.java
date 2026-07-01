package de.caritas.cob.userservice.api.port.out;

import static de.caritas.cob.userservice.api.model.Session.RegistrationType.ANONYMOUS;
import static de.caritas.cob.userservice.api.model.Session.RegistrationType.REGISTERED;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_ID_OFFENDER;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.testConfig.ConsultingTypeManagerTestConfig;
import java.time.LocalDateTime;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.ANY)
@Import({ConsultingTypeManagerTestConfig.class})
class SessionRepositoryQueueCountIT {

  @Autowired private SessionRepository sessionRepository;

  @Autowired private UserRepository userRepository;

  @Value("${user.anonymous.deactivateworkflow.periodMinutes}")
  private long liveChatQueueActivePeriodMinutes;

  private User user;
  private LocalDateTime referenceCreateDate;

  @BeforeEach
  void setup() {
    user = userRepository.findAll().iterator().next();
    user.setDataPrivacyConfirmation(LocalDateTime.now());
    user = userRepository.save(user);
    referenceCreateDate = LocalDateTime.now();
  }

  @AfterEach
  void cleanup() {
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  @Test
  void countPendingEnquiriesAheadOf_Should_excludeStaleSessions() {
    saveSession(
        session ->
            session.setUpdateDate(
                LocalDateTime.now().minusMinutes(liveChatQueueActivePeriodMinutes + 1)));
    saveSession(session -> session.setUpdateDate(LocalDateTime.now()));

    var minUpdateDate = LocalDateTime.now().minusMinutes(liveChatQueueActivePeriodMinutes);
    long count =
        sessionRepository.countPendingEnquiriesAheadOf(
            NEW,
            referenceCreateDate,
            CONSULTING_TYPE_ID_OFFENDER,
            null,
            null,
            minUpdateDate,
            ANONYMOUS);

    assertThat(count, is(1L));
  }

  @Test
  void countPendingEnquiriesAheadOf_Should_includeLiveChatStyleRegisteredSessions() {
    saveSession(
        session -> {
          session.setRegistrationType(REGISTERED);
          session.setPostcode("00000");
          session.setCreateDate(referenceCreateDate.minusMinutes(5));
          session.setUpdateDate(LocalDateTime.now());
        });

    var minUpdateDate = LocalDateTime.now().minusMinutes(liveChatQueueActivePeriodMinutes);
    long count =
        sessionRepository.countPendingEnquiriesAheadOf(
            NEW,
            referenceCreateDate,
            CONSULTING_TYPE_ID_OFFENDER,
            null,
            null,
            minUpdateDate,
            ANONYMOUS);

    assertThat(count, is(1L));
  }

  private void saveSession(java.util.function.Consumer<Session> customizer) {
    Session session = new EasyRandom().nextObject(Session.class);
    session.setId(null);
    session.setUser(user);
    session.setConsultant(null);
    session.setRegistrationType(ANONYMOUS);
    session.setStatus(NEW);
    session.setConsultingTypeId(CONSULTING_TYPE_ID_OFFENDER);
    session.setMainTopicId(null);
    session.setPostcode("12345");
    session.setCreateDate(referenceCreateDate.minusMinutes(1));
    session.setUpdateDate(LocalDateTime.now());
    customizer.accept(session);
    sessionRepository.save(session);
  }
}
