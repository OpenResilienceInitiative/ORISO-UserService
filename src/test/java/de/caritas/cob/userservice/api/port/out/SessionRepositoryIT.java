package de.caritas.cob.userservice.api.port.out;

import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_ARCHIVE;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.SessionData;
import de.caritas.cob.userservice.api.model.SessionData.SessionDataType;
import de.caritas.cob.userservice.api.model.User;
import java.util.List;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.ANY)
class SessionRepositoryIT {

  @Autowired private SessionRepository underTest;
  @Autowired private UserRepository userRepository;
  @Autowired private ConsultantRepository consultantRepository;

  @Test
  void saveShouldSaveSession() {
    var session = givenASession();

    var foundOptionalSession = underTest.findById(session.getId());
    assertTrue(foundOptionalSession.isPresent());

    var foundSession = foundOptionalSession.get();
    var sessionData = session.getSessionData();
    assertEquals(2, sessionData.size());
    assertEquals(sessionData.get(0), foundSession.getSessionData().get(0));
    assertEquals(sessionData.get(1), foundSession.getSessionData().get(1));
    assertFalse(foundSession.isTeamSession());
    assertFalse(foundSession.isPeerChat());
  }

  @Test
  void findByConsultantAndStatusIn_Should_returnOnlySessionsWithMatchingStatuses() {
    var consultant = givenAConsultant();
    var sessionInProgress = givenASessionForConsultant(consultant, IN_PROGRESS);
    var sessionInArchive = givenASessionForConsultant(consultant, IN_ARCHIVE);
    var sessionNew = givenASessionForConsultant(consultant, NEW);

    var result =
        underTest.findByConsultantAndStatusIn(consultant, List.of(IN_PROGRESS, IN_ARCHIVE));

    assertEquals(2, result.size());
    assertTrue(result.stream().anyMatch(s -> s.getId().equals(sessionInProgress.getId())));
    assertTrue(result.stream().anyMatch(s -> s.getId().equals(sessionInArchive.getId())));
    assertFalse(result.stream().anyMatch(s -> s.getId().equals(sessionNew.getId())));
  }

  @Test
  void findByConsultantAndStatusIn_Should_notReturnSessionsOfOtherConsultants() {
    var consultant = givenAConsultant();
    var otherConsultant = givenAnotherConsultant();
    var sessionOfConsultant = givenASessionForConsultant(consultant, IN_PROGRESS);
    var sessionOfOtherConsultant = givenASessionForConsultant(otherConsultant, IN_PROGRESS);

    var result = underTest.findByConsultantAndStatusIn(consultant, List.of(IN_PROGRESS));

    assertEquals(1, result.size());
    assertTrue(result.stream().anyMatch(s -> s.getId().equals(sessionOfConsultant.getId())));
    assertFalse(result.stream().anyMatch(s -> s.getId().equals(sessionOfOtherConsultant.getId())));
  }

  private User givenAUser() {
    return userRepository.findAll().iterator().next();
  }

  private Consultant givenAConsultant() {
    return consultants().get(0);
  }

  private Consultant givenAnotherConsultant() {
    return consultants().get(1);
  }

  private List<Consultant> consultants() {
    return (List<Consultant>) consultantRepository.findAll();
  }

  private Session givenASession() {
    var session = new Session();
    session.setUser(givenAUser());
    session.setConsultingTypeId(1);
    session.setRegistrationType(RegistrationType.REGISTERED);
    session.setPostcode(RandomStringUtils.randomNumeric(5));
    session.setLanguageCode(LanguageCode.de);
    session.setStatus(IN_PROGRESS);
    session.setIsConsultantDirectlySet(false);

    var sessionData1 =
        new SessionData(
            session,
            SessionDataType.REGISTRATION,
            RandomStringUtils.randomAlphanumeric(1, 255),
            RandomStringUtils.randomAlphanumeric(1, 255));
    var sessionData2 =
        new SessionData(
            session,
            SessionDataType.REGISTRATION,
            RandomStringUtils.randomAlphanumeric(1, 255),
            RandomStringUtils.randomAlphanumeric(1, 255));
    session.setSessionData(List.of(sessionData1, sessionData2));

    return underTest.save(session);
  }

  private Session givenASessionForConsultant(Consultant consultant, SessionStatus status) {
    var session = new Session();
    session.setConsultant(consultant);
    session.setUser(givenAUser());
    session.setConsultingTypeId(1);
    session.setRegistrationType(RegistrationType.REGISTERED);
    session.setPostcode(RandomStringUtils.randomNumeric(5));
    session.setLanguageCode(LanguageCode.de);
    session.setStatus(status);
    session.setIsConsultantDirectlySet(false);
    return underTest.save(session);
  }
}
