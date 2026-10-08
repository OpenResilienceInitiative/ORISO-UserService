package de.caritas.cob.userservice.api.port.out;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.SessionData;
import de.caritas.cob.userservice.api.model.SessionData.SessionDataType;
import de.caritas.cob.userservice.api.model.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.RandomStringUtils;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class SessionRepositoryIT {

  private static final EasyRandom easyRandom = new EasyRandom();

  @Autowired private SessionRepository underTest;

  @Autowired private UserRepository userRepository;

  @Autowired private EntityManager entityManager;
  @Autowired private EntityManagerFactory entityManagerFactory;

  private User user;

  private Session session;

  @AfterEach
  public void reset() {
    if (session != null) {
      underTest.delete(session);
    }
    session = null;
    user = null;
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void staleSessionUpdatesPreserveTheIndependentPreferenceAndUnrelatedData() {
    givenAUser();
    givenValidSession();
    session = underTest.save(session);
    Long id = session.getId();
    try (var staleWriter = entityManagerFactory.createEntityManager();
        var preferenceWriter = entityManagerFactory.createEntityManager()) {
      var stale = staleWriter.find(Session.class, id);
      preferenceWriter.getTransaction().begin();
      preferenceWriter.find(Session.class, id).setAlwaysAskBeforeAdditionalAccess(true);
      preferenceWriter.getTransaction().commit();
      staleWriter.getTransaction().begin();
      stale.setPostcode("54321");
      staleWriter.getTransaction().commit();
    }
    var after = underTest.findById(id).orElseThrow();
    assertTrue(after.isAlwaysAskBeforeAdditionalAccess());
    assertEquals("54321", after.getPostcode());
    try (var stalePreferenceWriter = entityManagerFactory.createEntityManager();
        var unrelatedWriter = entityManagerFactory.createEntityManager()) {
      var stale = stalePreferenceWriter.find(Session.class, id);
      unrelatedWriter.getTransaction().begin();
      unrelatedWriter.find(Session.class, id).setPostcode("12345");
      unrelatedWriter.getTransaction().commit();
      stalePreferenceWriter.getTransaction().begin();
      stale.setAlwaysAskBeforeAdditionalAccess(false);
      stalePreferenceWriter.getTransaction().commit();
    }
    var finalState = underTest.findById(id).orElseThrow();
    assertFalse(finalState.isAlwaysAskBeforeAdditionalAccess());
    assertEquals("12345", finalState.getPostcode());
    session = finalState;
  }

  @Test
  void standingAdditionalAccessPreferenceSurvivesClearingThePersistenceContext() {
    givenAUser();
    givenValidSession();
    var saved = underTest.save(session);
    entityManager.flush();
    Long id = saved.getId();
    entityManager.clear();
    assertFalse(underTest.findById(id).orElseThrow().isAlwaysAskBeforeAdditionalAccess());
    var reloaded = underTest.findById(id).orElseThrow();
    reloaded.setAlwaysAskBeforeAdditionalAccess(true);
    underTest.save(reloaded);
    entityManager.flush();
    entityManager.clear();
    assertTrue(underTest.findById(id).orElseThrow().isAlwaysAskBeforeAdditionalAccess());
    var enabled = underTest.findById(id).orElseThrow();
    enabled.setAlwaysAskBeforeAdditionalAccess(false);
    underTest.save(enabled);
    entityManager.flush();
    entityManager.clear();
    assertFalse(underTest.findById(id).orElseThrow().isAlwaysAskBeforeAdditionalAccess());
    session = underTest.findById(id).orElseThrow();
  }

  @Test
  void saveShouldSaveSession() {
    givenAUser();
    givenValidSession();

    var persistedSession = underTest.save(session);

    var foundOptionalSession = underTest.findById(persistedSession.getId());
    assertTrue(foundOptionalSession.isPresent());

    var foundSession = foundOptionalSession.get();
    var sessionData = session.getSessionData();
    assertEquals(2, sessionData.size());
    assertEquals(sessionData.get(0), foundSession.getSessionData().get(0));
    assertEquals(sessionData.get(1), foundSession.getSessionData().get(1));
    assertFalse(foundSession.isTeamSession());
    assertEquals(ConversationType.AGENCY_COUNSELLING, foundSession.getConversationType());
  }

  @Test
  void saveShouldRepairANullConversationTypeDuringRollingDeployment() {
    givenAUser();
    givenValidSession();
    session.setConversationType(null);
    var persistedSession = underTest.save(session);
    entityManager.flush();

    persistedSession.setConversationType(ConversationType.AGENCY_COUNSELLING);
    underTest.save(persistedSession);
    entityManager.flush();
    entityManager.clear();

    assertEquals(
        ConversationType.AGENCY_COUNSELLING,
        underTest.findById(persistedSession.getId()).orElseThrow().getConversationType());
  }

  @Test
  void findLowestConsultingTypeIdsByAgencyIdsShouldGroupAndSelectMinimum() {
    givenAUser();
    var sessions =
        List.of(
            validSession(10L, 4), validSession(10L, 2), validSession(20L, 7), validSession(30L, 9));
    underTest.saveAll(sessions);
    entityManager.flush();

    try {
      var consultingTypesByAgency =
          underTest.findLowestConsultingTypeIdsByAgencyIds(Set.of(10L, 20L)).stream()
              .collect(
                  Collectors.toMap(
                      SessionRepository.AgencyConsultingTypeProjection::getAgencyId,
                      SessionRepository.AgencyConsultingTypeProjection::getConsultingTypeId));

      assertEquals(Map.of(10L, 2, 20L, 7), consultingTypesByAgency);
    } finally {
      underTest.deleteAll(sessions);
    }
  }

  private void givenValidSession() {
    session = validSession(null, 1);
  }

  private Session validSession(Long agencyId, int consultingTypeId) {
    var validSession = new Session();
    validSession.setUser(user);
    validSession.setAgencyId(agencyId);
    validSession.setConsultingTypeId(consultingTypeId);
    validSession.setRegistrationType(easyRandom.nextObject(RegistrationType.class));
    validSession.setPostcode(RandomStringUtils.randomNumeric(5));
    validSession.setLanguageCode(easyRandom.nextObject(LanguageCode.class));
    validSession.setStatus(easyRandom.nextObject(SessionStatus.class));
    validSession.setIsConsultantDirectlySet(false);
    validSession.setConversationType(ConversationType.AGENCY_COUNSELLING);

    var sessionData1 =
        new SessionData(
            validSession,
            SessionDataType.REGISTRATION,
            RandomStringUtils.randomAlphanumeric(1, 255),
            RandomStringUtils.randomAlphanumeric(1, 255));

    var sessionData2 =
        new SessionData(
            validSession,
            SessionDataType.REGISTRATION,
            RandomStringUtils.randomAlphanumeric(1, 255),
            RandomStringUtils.randomAlphanumeric(1, 255));

    validSession.setSessionData(List.of(sessionData1, sessionData2));
    return validSession;
  }

  private void givenAUser() {
    user = userRepository.findAll().iterator().next();
  }
}
