package de.caritas.cob.userservice.api.port.out;

import static com.neovisionaries.i18n.LanguageCode.de;
import static de.caritas.cob.userservice.api.model.Session.RegistrationType.ANONYMOUS;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_ID_OFFENDER;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.testConfig.ConsultingTypeManagerTestConfig;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * The anonymous Live Chat queue must show enquiries whose advice seeker has no recorded {@code
 * dataPrivacyConfirmation}.
 *
 * <p>This is not an oversight in the data: {@code CreateUserFacade} clears that field for anonymous
 * registrations <b>on purpose</b>, so the in-chat consent gate fires (ADR-018 §9, #927). The
 * visibility query used to require it anyway, which meant the two deliberate decisions cancelled
 * each other out and <b>every</b> anonymous live-chat enquiry was invisible to <b>every</b>
 * consultant — the asker clicked, nothing happened, and nobody ever saw the request (#431).
 *
 * <p>Consent is collected at entry, from the platform-level live-chat privacy notice, and {@code
 * AnonymousEnquiryConsentGuard} still blocks the <b>assignment</b> server-side. Queue visibility
 * does not need to enforce the same consent a second time.
 */
@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({ConsultingTypeManagerTestConfig.class})
@Transactional
class SessionRepositoryAnonymousQueueVisibilityIT {

  private static final long TOPIC_ID = 3L;

  @Autowired private SessionRepository sessionRepository;

  @Autowired private UserRepository userRepository;

  @Value("${user.anonymous.deactivateworkflow.periodMinutes}")
  private long liveChatQueueActivePeriodMinutes;

  private User user;

  @BeforeEach
  void setup() {
    user = userRepository.findAll().iterator().next();
    // Exactly what an anonymous registration leaves behind.
    user.setDataPrivacyConfirmation(null);
    user = userRepository.save(user);
  }

  @Test
  void findAnonymousEnquiries_Should_includeSessionsOfAskersWithoutRecordedConsent() {
    var session = saveAnonymousSession();

    var visible = queryQueue();

    assertThat(
        visible.stream().map(Session::getId).collect(Collectors.toList()),
        hasItem(session.getId()));
  }

  @Test
  void findAnonymousEnquiries_Should_stillExcludeStaleSessions() {
    saveAnonymousSession(
        stale ->
            stale.setUpdateDate(
                LocalDateTime.now().minusMinutes(liveChatQueueActivePeriodMinutes + 1)));

    assertThat(queryQueue().size(), is(0));
  }

  @Test
  void findAnonymousEnquiries_Should_stillExcludeAlreadyAssignedSessions() {
    // A session someone has already picked up must never reappear in the queue.
    saveAnonymousSession(assigned -> assigned.setStatus(Session.SessionStatus.IN_PROGRESS));

    assertThat(queryQueue().size(), is(0));
  }

  private java.util.List<Session> queryQueue() {
    return sessionRepository
        .findAnonymousEnquiriesVisibleForConsultantsByTopicsOnly(
            Set.of(TOPIC_ID),
            NEW,
            LocalDateTime.now().minusMinutes(liveChatQueueActivePeriodMinutes),
            ANONYMOUS,
            PageRequest.of(0, 20))
        .getContent();
  }

  private Session saveAnonymousSession() {
    return saveAnonymousSession(session -> {});
  }

  private Session saveAnonymousSession(java.util.function.Consumer<Session> customizer) {
    Session session = new Session(user, CONSULTING_TYPE_ID_OFFENDER, "00000", null, NEW, false);
    session.setRegistrationType(ANONYMOUS);
    session.setIsConsultantDirectlySet(false);
    session.setLanguageCode(de);
    session.setMainTopicId(TOPIC_ID);
    session.setCreateDate(LocalDateTime.now().minusMinutes(1));
    session.setUpdateDate(LocalDateTime.now());
    customizer.accept(session);
    return sessionRepository.save(session);
  }
}
