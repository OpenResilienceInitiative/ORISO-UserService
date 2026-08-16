package de.caritas.cob.userservice.api.service.session;

import static com.neovisionaries.i18n.LanguageCode.de;
import static de.caritas.cob.userservice.api.model.Session.RegistrationType.ANONYMOUS;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTING_TYPE_ID_OFFENDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.testConfig.ConsultingTypeManagerTestConfig;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-022 decision 2 end to end through the persistence layer: the consent pointer survives a
 * round-trip and re-consent <b>overwrites</b> it.
 *
 * <p>The overwrite test is the point of this class. If anybody ever "improves" the pointer into an
 * append-only history — a second row, a version list, a consent event table — this test is where it
 * breaks, and the ADR is the reason it must stay broken: a per-user consent log would create a
 * behavioural record about anonymous help-seekers that does not exist today.
 */
@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({ConsultingTypeManagerTestConfig.class})
@Transactional
class SessionConsentPointerIT {

  @Autowired private SessionConsentService sessionConsentService;

  @Autowired private SessionRepository sessionRepository;

  @Autowired private UserRepository userRepository;

  private User adviceSeeker;

  @BeforeEach
  void setup() {
    adviceSeeker = userRepository.findAll().iterator().next();
    // Exactly what an anonymous registration leaves behind (CreateUserFacade, by design).
    adviceSeeker.setDataPrivacyConfirmation(null);
    adviceSeeker = userRepository.save(adviceSeeker);
  }

  @Test
  void recordConsent_Should_persistThePointerOnTheSession() {
    var session = saveSession();

    sessionConsentService.recordConsent(session.getId(), adviceSeeker, 7L);

    assertThat(reload(session.getId()).getConsentedLegalVersionId()).isEqualTo(7L);
  }

  @Test
  void recordConsent_Should_overwriteThePointer_When_theTextChangedAndWasAgreedToAgain() {
    var session = saveSession();
    sessionConsentService.recordConsent(session.getId(), adviceSeeker, 7L);

    sessionConsentService.recordConsent(session.getId(), adviceSeeker, 8L);

    // One value, not two. No history, by design.
    assertThat(reload(session.getId()).getConsentedLegalVersionId()).isEqualTo(8L);
  }

  @Test
  void recordConsent_Should_refuseARoomTheCallerDoesNotOwn() {
    var session = saveSession();
    var stranger = new User();
    stranger.setUserId("someone-else");

    assertThatThrownBy(() -> sessionConsentService.recordConsent(session.getId(), stranger, 7L))
        .isInstanceOf(ForbiddenException.class);
    assertThat(reload(session.getId()).getConsentedLegalVersionId()).isNull();
  }

  private Session reload(Long sessionId) {
    return sessionRepository.findById(sessionId).orElseThrow();
  }

  private Session saveSession() {
    Session session =
        new Session(adviceSeeker, CONSULTING_TYPE_ID_OFFENDER, "00000", null, NEW, false);
    session.setRegistrationType(ANONYMOUS);
    session.setIsConsultantDirectlySet(false);
    session.setLanguageCode(de);
    session.setCreateDate(LocalDateTime.now().minusMinutes(1));
    session.setUpdateDate(LocalDateTime.now());
    return sessionRepository.save(session);
  }
}
