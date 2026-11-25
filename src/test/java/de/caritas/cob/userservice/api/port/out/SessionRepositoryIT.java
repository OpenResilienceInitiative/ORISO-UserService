package de.caritas.cob.userservice.api.port.out;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.CaseSharingModel;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.SessionData;
import de.caritas.cob.userservice.api.model.SessionData.SessionDataType;
import de.caritas.cob.userservice.api.model.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.RandomStringUtils;
import org.hibernate.SessionFactory;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

  @Autowired private ConsultantRepository consultantRepository;

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
    try (var staleWriter = entityManagerFactory.createEntityManager()) {
      var stale = staleWriter.find(Session.class, id);
      underTest.updateAdditionalAccessPreference(id, true);
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
      // Preference writes intentionally use the dedicated production boundary, not stale entities.
      assertTrue(stale.isAlwaysAskBeforeAdditionalAccess());
      underTest.updateAdditionalAccessPreference(id, false);
    }
    var finalState = underTest.findById(id).orElseThrow();
    assertFalse(finalState.isAlwaysAskBeforeAdditionalAccess());
    assertEquals("12345", finalState.getPostcode());
    session = finalState;
  }

  /** ORISO-Frontend#2170: the name of the direct link is written once, like its medium. */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void directLinkNameIsWrittenOnceAndSurvivesStaleSaves() {
    givenAUser();
    givenValidSession();
    session = underTest.save(session);
    Long id = session.getId();
    Session stale;
    try (var detachedReader = entityManagerFactory.createEntityManager()) {
      stale = detachedReader.find(Session.class, id);
    }
    var version = stale.getRowVersion();

    assertEquals(1, underTest.recordDirectLinkName(id, "Flyer Schule"));
    // A later link of another name does not rename the conversation.
    assertEquals(0, underTest.recordDirectLinkName(id, "Plakat Bahnhof"));
    assertEquals("Flyer Schule", underTest.findById(id).orElseThrow().getDirectLinkName());
    assertEquals(version, underTest.findById(id).orElseThrow().getRowVersion());

    // A whole-entity save from before the write must not erase it.
    stale.setPostcode("54321");
    underTest.save(stale);
    var finalState = underTest.findById(id).orElseThrow();
    session = finalState;
    assertEquals("Flyer Schule", finalState.getDirectLinkName());
    assertEquals("54321", finalState.getPostcode());
  }

  /** FE#1952: the medium of the direct link a conversation came from is written once. */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void directLinkOriginIsWrittenOnceAndSurvivesStaleSaves() {
    givenAUser();
    givenValidSession();
    session = underTest.save(session);
    Long id = session.getId();
    Session stale;
    try (var detachedReader = entityManagerFactory.createEntityManager()) {
      stale = detachedReader.find(Session.class, id);
    }
    var version = stale.getRowVersion();

    assertEquals(1, underTest.recordDirectLinkOrigin(id, "VIDEO"));
    // A later link of another medium does not relabel the conversation.
    assertEquals(0, underTest.recordDirectLinkOrigin(id, "AUDIO"));
    assertEquals("VIDEO", underTest.findById(id).orElseThrow().getDirectLinkMedium());
    assertEquals(version, underTest.findById(id).orElseThrow().getRowVersion());

    // A whole-entity save from before the write must not erase it.
    stale.setPostcode("54321");
    underTest.save(stale);
    var finalState = underTest.findById(id).orElseThrow();
    session = finalState;
    assertEquals("VIDEO", finalState.getDirectLinkMedium());
    assertEquals("54321", finalState.getPostcode());
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void detachedStaleSessionSaveCannotRevertAnIndependentPreferenceWrite() {
    givenAUser();
    givenValidSession();
    session = underTest.save(session);
    Long id = session.getId();
    Session stale;
    try (var detachedReader = entityManagerFactory.createEntityManager()) {
      stale = detachedReader.find(Session.class, id);
    }
    var ownershipVersion = stale.getRowVersion();
    underTest.updateAdditionalAccessPreference(id, true);
    assertEquals(ownershipVersion, underTest.findById(id).orElseThrow().getRowVersion());
    stale.setPostcode("54321");
    underTest.save(stale);
    var finalState = underTest.findById(id).orElseThrow();
    session = finalState;
    assertTrue(finalState.isAlwaysAskBeforeAdditionalAccess());
    assertEquals("54321", finalState.getPostcode());
    assertEquals(ownershipVersion + 1, finalState.getRowVersion());
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void staleWholeSessionSaveStillRejectsAConcurrentStatusWrite() {
    givenAUser();
    givenValidSession();
    session = underTest.save(session);
    Long id = session.getId();
    Session stale;
    try (var reader = entityManagerFactory.createEntityManager()) {
      stale = reader.find(Session.class, id);
    }
    var current = underTest.findById(id).orElseThrow();
    current.setStatus(SessionStatus.DONE);
    session = underTest.save(current);
    stale.setPostcode("54321");
    assertThrows(
        org.springframework.orm.ObjectOptimisticLockingFailureException.class,
        () -> underTest.save(stale));
    var finalState = underTest.findById(id).orElseThrow();
    assertEquals(SessionStatus.DONE, finalState.getStatus());
    assertEquals(session.getPostcode(), finalState.getPostcode());
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
    underTest.updateAdditionalAccessPreference(id, true);
    entityManager.flush();
    entityManager.clear();
    assertTrue(underTest.findById(id).orElseThrow().isAlwaysAskBeforeAdditionalAccess());
    underTest.updateAdditionalAccessPreference(id, false);
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

  @Test
  void countOpenCasesOfTenantCountsOnlyOpenCasesOfThatTenant() {
    givenAUser();
    var consultant = consultantRepository.findAll().iterator().next();
    var id = consultant.getId();
    var open = List.of(SessionStatus.NEW, SessionStatus.IN_PROGRESS);
    // This consultant may already have seeded open cases, which count for tenant 1.
    long seededInTenant1 = underTest.countOpenCasesOfTenant(id, open, 1L);
    var sessions =
        List.of(
            sessionOf(consultant, SessionStatus.NEW, 11L),
            sessionOf(consultant, SessionStatus.IN_PROGRESS, 11L),
            sessionOf(consultant, SessionStatus.IN_PROGRESS, 22L),
            sessionOf(consultant, SessionStatus.INITIAL, 11L),
            sessionOf(consultant, SessionStatus.IN_PROGRESS, null));
    underTest.saveAll(sessions);
    entityManager.flush();

    try {
      assertEquals(2, underTest.countOpenCasesOfTenant(id, open, 11L));
      assertEquals(1, underTest.countOpenCasesOfTenant(id, open, 22L));
      assertEquals(0, underTest.countOpenCasesOfTenant(id, open, 33L));
      // Cases without a tenant belong to the technical tenant 1, as in the room queries.
      assertEquals(seededInTenant1 + 1, underTest.countOpenCasesOfTenant(id, open, 1L));
      assertEquals(0, underTest.countOpenCasesOfTenant(id, open, null));
    } finally {
      underTest.deleteAll(sessions);
    }
  }

  private Session sessionOf(Consultant consultant, SessionStatus status, Long tenantId) {
    var result = validSession(null, 1);
    result.setConsultant(consultant);
    result.setStatus(status);
    result.setTenantId(tenantId);
    return result;
  }

  /**
   * ORISO-Frontend#2252: the shared conversation of a direct invitation with an appointment is a
   * session of the same counsellor, person and consulting type as their 1:1, and a person can have
   * several. Looking up the 1:1 must neither return one of those nor fail on their number, also
   * after the invitations were deleted (no link rows here at all).
   */
  @Test
  void findOneToOneSessions_Should_LeaveOutSharedConversations_AlsoAfterTheirLinksAreGone() {
    givenAUser();
    var consultant = consultantRepository.findAll().iterator().next();
    var oneToOne = validSession(null, 1);
    oneToOne.setConsultant(consultant);
    oneToOne = underTest.save(oneToOne);
    // An ordinary appointment of the 1:1 ("Termin anlegen") keeps it the 1:1.
    appointmentOf(oneToOne);
    for (int i = 0; i < 2; i++) {
      var shared = validSession(null, 1);
      shared.setConsultant(consultant);
      shared = underTest.save(shared);
      appointmentOf(shared);
      underTest.markDirectInvitationConversation(shared.getId());
    }
    entityManager.flush();
    entityManager.clear();

    var found = underTest.findOneToOneSessions(consultant, user, 1);

    assertEquals(List.of(oneToOne.getId()), found.stream().map(Session::getId).toList());
  }

  /**
   * FE#2494 *4: the repair touches only Teamberatung 1:1 cases marked read-only at accept or
   * reassignment, never unmarked (pre-release) cases and never group chats.
   */
  @Test
  void findTeamReadOnlyCandidates_Should_KeepOnlyMarkedOneToOneTeamCases() {
    givenAUser();
    var consultant = consultantRepository.findAll().iterator().next();
    var ids = new java.util.ArrayList<Long>();
    for (var type :
        java.util.Arrays.asList(
            ConversationType.AGENCY_COUNSELLING, null, ConversationType.INTERNAL_GROUP)) {
      var team = validSession(null, 1);
      team.setConsultant(consultant);
      team.setTeamSession(true);
      team.setStatus(SessionStatus.IN_PROGRESS);
      team.setMatrixRoomId("!team-" + ids.size() + ":matrix.example");
      team.setConversationType(type);
      // marked in the accepting save (SessionOwnershipService), group chat included on purpose
      team.setTeamReadOnlySince(java.time.LocalDateTime.now());
      ids.add(underTest.save(team).getId());
    }
    var legacy = validSession(null, 1);
    legacy.setConsultant(consultant);
    legacy.setTeamSession(true);
    legacy.setStatus(SessionStatus.IN_PROGRESS);
    legacy.setMatrixRoomId("!legacy:matrix.example");
    var legacyId = underTest.save(legacy).getId();
    // the stored stamp wins over the team flag, in both directions
    var stampedTeam = markedOneToOne(consultant, "!stamped-team:matrix.example", false);
    stampedTeam.setCaseSharingModel(CaseSharingModel.TEAM_COUNSELLING);
    var stampedTeamId = underTest.save(stampedTeam).getId();
    var stampedNone = markedOneToOne(consultant, "!stamped-none:matrix.example", true);
    stampedNone.setCaseSharingModel(CaseSharingModel.NONE);
    var stampedNoneId = underTest.save(stampedNone).getId();
    entityManager.flush();
    entityManager.clear();

    var found =
        underTest.findTeamReadOnlyCandidates(
            SessionStatus.IN_PROGRESS,
            ids.get(0) - 1,
            org.springframework.data.domain.PageRequest.of(0, 50));

    assertEquals(
        List.of(ids.get(0), ids.get(1), stampedTeamId), found, "group chats are never repaired");
    assertFalse(found.contains(stampedNoneId), "a case stamped none is no team case");
    assertFalse(found.contains(legacyId), "a case accepted before read-only stays as it is");
  }

  /**
   * FE#2494 *4: the snapshot stamps unstamped 1:1 cases with a centre once, never group chats or
   * live chats (also legacy ones without a modality), and a stamp is never overwritten, not even by
   * a stale whole-entity save.
   */
  @Test
  void findUnstampedCases_Should_SkipGroupAndLiveChatsAndStampedCases_AndStampOnlyOnce() {
    givenAUser();
    var ids = new java.util.ArrayList<Long>();
    for (var type :
        java.util.Arrays.asList(
            ConversationType.AGENCY_COUNSELLING,
            null,
            ConversationType.INTERNAL_GROUP,
            ConversationType.SELF_HELP,
            ConversationType.LIVE_CHAT)) {
      var unstamped = validSession(null, 1);
      unstamped.setAgencyId(5L);
      unstamped.setConversationType(type);
      unstamped.setRegistrationType(RegistrationType.REGISTERED);
      unstamped.setPostcode("12345");
      unstamped.setTeamSession(type == null);
      ids.add(underTest.save(unstamped).getId());
    }
    var legacyLiveChat = validSession(null, 1);
    legacyLiveChat.setAgencyId(5L);
    legacyLiveChat.setConversationType(null);
    legacyLiveChat.setRegistrationType(RegistrationType.ANONYMOUS);
    var legacyLiveChatId = underTest.save(legacyLiveChat).getId();
    var stamped = validSession(null, 1);
    stamped.setAgencyId(5L);
    stamped.setCaseSharingModel(de.caritas.cob.userservice.api.model.CaseSharingModel.NONE);
    var stampedId = underTest.save(stamped).getId();
    entityManager.flush();
    entityManager.clear();

    var found =
        underTest
            .findUnstampedCases(
                ids.get(0) - 1,
                RegistrationType.ANONYMOUS,
                org.springframework.data.domain.PageRequest.of(0, 50))
            .stream()
            .toList();

    var foundIds = found.stream().map(SessionRepository.UnstampedCase::getId).toList();
    assertEquals(ids.subList(0, 2), foundIds, "found " + foundIds + " of " + ids);
    assertFalse(foundIds.contains(legacyLiveChatId), "a legacy live chat is no counselling case");
    assertFalse(foundIds.contains(stampedId));
    assertEquals(5L, found.get(1).getAgencyId());
    assertTrue(found.get(1).isTeamSession());
    assertFalse(found.get(0).isTeamSession());

    // The cursor and the page size hold.
    assertEquals(
        List.of(ids.get(1)),
        underTest
            .findUnstampedCases(
                ids.get(0),
                RegistrationType.ANONYMOUS,
                org.springframework.data.domain.PageRequest.of(0, 1))
            .stream()
            .map(SessionRepository.UnstampedCase::getId)
            .toList());

    assertEquals(
        1,
        underTest.stampCaseSharingModel(
            ids.get(0), de.caritas.cob.userservice.api.model.CaseSharingModel.CASE_HANDOVER));
    assertEquals(
        0,
        underTest.stampCaseSharingModel(
            stampedId, de.caritas.cob.userservice.api.model.CaseSharingModel.CASE_HANDOVER));
    entityManager.clear();
    assertEquals(
        de.caritas.cob.userservice.api.model.CaseSharingModel.NONE,
        underTest.findById(stampedId).orElseThrow().getCaseSharingModel());

    // A stale whole-entity save cannot write over the stamp: the column is write-once.
    var stale = underTest.findById(stampedId).orElseThrow();
    stale.setCaseSharingModel(null);
    underTest.save(stale);
    entityManager.flush();
    entityManager.clear();
    assertEquals(
        de.caritas.cob.userservice.api.model.CaseSharingModel.NONE,
        underTest.findById(stampedId).orElseThrow().getCaseSharingModel());

    assertEquals(
        List.of(ids.get(1)),
        underTest
            .findUnstampedCases(
                ids.get(0) - 1,
                RegistrationType.ANONYMOUS,
                org.springframework.data.domain.PageRequest.of(0, 50))
            .stream()
            .map(SessionRepository.UnstampedCase::getId)
            .toList());
  }

  /** FE#2494 *4: an open team case, an enquiry not yet accepted included, keeps team access. */
  @Test
  void existsTeamCase_Should_CountOpenTeamCasesByStampOrFlag() {
    givenAUser();
    var open = List.of(SessionStatus.INITIAL, SessionStatus.NEW, SessionStatus.IN_PROGRESS);
    java.util.function.BiFunction<Long, SessionStatus, Session> teamCase =
        (agencyId, status) -> {
          var session = validSession(agencyId, 1);
          session.setStatus(status);
          session.setTeamSession(true);
          return session;
        };

    underTest.save(teamCase.apply(7101L, SessionStatus.NEW));
    underTest.save(teamCase.apply(7102L, SessionStatus.DONE));
    var group = teamCase.apply(7103L, SessionStatus.IN_PROGRESS);
    group.setConversationType(ConversationType.INTERNAL_GROUP);
    underTest.save(group);
    var stampedNone = teamCase.apply(7104L, SessionStatus.IN_PROGRESS);
    stampedNone.setCaseSharingModel(de.caritas.cob.userservice.api.model.CaseSharingModel.NONE);
    underTest.save(stampedNone);
    var stampedTeam = validSession(7105L, 1);
    stampedTeam.setStatus(SessionStatus.IN_PROGRESS);
    stampedTeam.setCaseSharingModel(
        de.caritas.cob.userservice.api.model.CaseSharingModel.TEAM_COUNSELLING);
    underTest.save(stampedTeam);
    entityManager.flush();
    entityManager.clear();

    assertTrue(underTest.existsTeamCase(7101L, open), "a pending team enquiry counts");
    assertFalse(underTest.existsTeamCase(7102L, open), "a finished case does not");
    assertFalse(underTest.existsTeamCase(7103L, open), "a group chat is no team case");
    assertFalse(underTest.existsTeamCase(7104L, open), "the stamp wins over the flag");
    assertTrue(underTest.existsTeamCase(7105L, open), "a stamped team case counts");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void handoverCandidateScanLoadsOnlyRequestedNamesWithoutLoadingPersonEntities(
      boolean includeNames) {
    givenAUser();
    var consultants = consultantRepository.findAll();
    var owner = consultants.get(0);
    var requester = consultants.get(1);
    var askerUsername = user.getUsername();
    var consultantUsername = owner.getUsername();
    owner.setDisplayName("Public Alias");
    owner.setInternalDisplayName("Internal Alias");
    owner.setFirstName("Given Name");
    owner.setLastName("Family Name");
    long agencyId = 98_765L;
    var candidate = validSession(agencyId, 1);
    candidate.setConsultant(owner);
    candidate.setStatus(SessionStatus.IN_PROGRESS);
    candidate = underTest.save(candidate);
    entityManager.flush();
    var candidateId = candidate.getId();
    entityManager.clear();

    var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    boolean enabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    statistics.clear();
    try {
      var results =
          underTest.findHandoverCandidates(
              List.of(agencyId), requester, List.of(SessionStatus.IN_PROGRESS), includeNames);
      assertEquals(1, results.size());
      var projected = results.getFirst();
      assertEquals(candidateId, projected.getId());
      var expectedNames =
          includeNames
              ? List.of(
                  askerUsername,
                  consultantUsername,
                  "Public Alias",
                  "Internal Alias",
                  "Given Name",
                  "Family Name")
              : Collections.nCopies(6, null);
      assertAll(
          () ->
              assertEquals(
                  expectedNames,
                  Arrays.asList(
                      projected.getAskerUsername(),
                      projected.getConsultantUsername(),
                      projected.getConsultantDisplayName(),
                      projected.getConsultantInternalDisplayName(),
                      projected.getConsultantFirstName(),
                      projected.getConsultantLastName())),
          () ->
              assertEquals(0, statistics.getEntityStatistics(User.class.getName()).getLoadCount()),
          () ->
              assertEquals(
                  0, statistics.getEntityStatistics(Consultant.class.getName()).getLoadCount()));
    } finally {
      statistics.setStatisticsEnabled(enabled);
    }
  }

  private void appointmentOf(Session session) {
    var appointment = new de.caritas.cob.userservice.api.model.SessionAppointment();
    appointment.setSession(session);
    appointment.setStatus(de.caritas.cob.userservice.api.model.SessionAppointment.Status.SCHEDULED);
    appointment.setStartTime(java.time.Instant.parse("2030-01-01T10:00:00Z"));
    appointment.setEndTime(java.time.Instant.parse("2030-01-01T11:00:00Z"));
    entityManager.persist(appointment);
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
    // fixed: EasyRandom's seeded sequence can draw LanguageCode.undefined, too long for the column
    validSession.setLanguageCode(LanguageCode.de);
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

  private Session markedOneToOne(Consultant consultant, String roomId, boolean teamFlag) {
    var session = validSession(null, 1);
    session.setConsultant(consultant);
    session.setTeamSession(teamFlag);
    session.setStatus(SessionStatus.IN_PROGRESS);
    session.setMatrixRoomId(roomId);
    session.setConversationType(ConversationType.AGENCY_COUNSELLING);
    session.setTeamReadOnlySince(java.time.LocalDateTime.now());
    return session;
  }
}
