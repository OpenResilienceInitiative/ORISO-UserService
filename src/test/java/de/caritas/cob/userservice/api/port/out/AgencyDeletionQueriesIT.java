package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.DraftMessage;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserAgency;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The queries a Beratungsstelle deletion runs (ORISO-Admin#1524), against the real schema: who is
 * deleted with the Beratungsstelle, and what advice seekers who keep their account lose.
 */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfiguration.class)
class AgencyDeletionQueriesIT {

  private static final long TENANT = 990_102L;
  private static final long AGENCY = 990_701L;
  private static final long OTHER_AGENCY = 990_702L;
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 10, 0);

  @Autowired private SessionRepository sessionRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private UserAgencyRepository userAgencyRepository;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private ConsultantAgencyRepository consultantAgencyRepository;
  @Autowired private DraftMessageRepository draftMessageRepository;
  @Autowired private EntityManager entityManager;

  private User onlyBySession;
  private User onlyByMembership;
  private User alsoElsewhereBySession;
  private User alsoElsewhereByMembership;
  private User alreadyMarked;
  private User elsewhereOnly;
  private Session keptSessionHere;
  private Session keptSessionElsewhere;
  private Session markedAskersSession;
  private Session technicalSession;

  @BeforeEach
  void seed() {
    onlyBySession = asker();
    session(onlyBySession, AGENCY, SessionStatus.NEW, null);

    onlyByMembership = asker();
    membership(onlyByMembership, AGENCY);

    alsoElsewhereBySession = asker();
    keptSessionHere =
        session(alsoElsewhereBySession, AGENCY, SessionStatus.IN_PROGRESS, counsellor());
    keptSessionElsewhere = session(alsoElsewhereBySession, OTHER_AGENCY, SessionStatus.NEW, null);

    alsoElsewhereByMembership = asker();
    session(alsoElsewhereByMembership, AGENCY, SessionStatus.DONE, null);
    membership(alsoElsewhereByMembership, AGENCY);
    membership(alsoElsewhereByMembership, OTHER_AGENCY);

    alreadyMarked = asker();
    alreadyMarked.setDeleteDate(NOW.minusDays(1));
    markedAskersSession = session(alreadyMarked, AGENCY, SessionStatus.DONE, null);

    elsewhereOnly = asker();
    session(elsewhereOnly, OTHER_AGENCY, SessionStatus.NEW, null);

    var technical = asker("group-chat-system-" + TENANT);
    technicalSession = session(technical, AGENCY, SessionStatus.IN_PROGRESS, null);

    entityManager.flush();
  }

  @Test
  void countActiveAdviceSeekersOnlyInAgency_Should_countThoseWithNoOtherAgency() {
    assertThat(userRepository.countActiveAdviceSeekersOnlyInAgency(AGENCY)).isEqualTo(2L);
  }

  @Test
  void
      findActiveAdviceSeekerIdsLinkedToAgency_Should_listActiveAdviceSeekersBySessionOrMembership() {
    assertThat(userRepository.findActiveAdviceSeekerIdsLinkedToAgency(AGENCY))
        .containsExactlyInAnyOrder(
            onlyBySession.getUserId(),
            onlyByMembership.getUserId(),
            alsoElsewhereBySession.getUserId(),
            alsoElsewhereByMembership.getUserId());
  }

  @Test
  void outsideAgencyChecks_Should_seeOnlyLinksElsewhere_andIgnoreMarkedSessions() {
    assertThat(
            sessionRepository.hasActiveSessionOutsideAgency(
                alsoElsewhereBySession.getUserId(), AGENCY))
        .isTrue();
    assertThat(sessionRepository.hasActiveSessionOutsideAgency(onlyBySession.getUserId(), AGENCY))
        .isFalse();
    assertThat(
            userAgencyRepository.hasMembershipOutsideAgency(
                alsoElsewhereByMembership.getUserId(), AGENCY))
        .isTrue();
    assertThat(
            userAgencyRepository.hasMembershipOutsideAgency(onlyByMembership.getUserId(), AGENCY))
        .isFalse();

    keptSessionElsewhere.setDeleteDate(NOW);
    entityManager.flush();
    assertThat(
            sessionRepository.hasActiveSessionOutsideAgency(
                alsoElsewhereBySession.getUserId(), AGENCY))
        .isFalse();
  }

  @Test
  void deleteLegacyDraftsOfSession_Should_removeOnlyTheDraftsWrittenInThatSession() {
    var bySessionId = draft("asker", keptSessionHere.getId(), null, null);
    var byRoomOnly = draft("counsellor", null, "!kept-here:matrix", null);
    var otherSession = draft("asker", keptSessionElsewhere.getId(), null, null);
    var otherRoom = draft("asker", null, "!elsewhere:matrix", null);
    var otherSessionSameRoomRef =
        draft("asker", keptSessionElsewhere.getId(), "!kept-here:matrix", null);
    var noReference = draft("asker", null, null, null);
    var protectedDraft = draft("asker", null, null, "draft.v1." + "a".repeat(43));
    entityManager.flush();

    int deleted =
        draftMessageRepository.deleteLegacyDraftsOfSession(
            keptSessionHere.getId(), "!kept-here:matrix");

    assertThat(deleted).isEqualTo(2);
    assertThat(draftMessageRepository.findAll())
        .extracting(DraftMessage::getId)
        .contains(
            otherSession.getId(),
            otherRoom.getId(),
            otherSessionSameRoomRef.getId(),
            noReference.getId(),
            protectedDraft.getId())
        .doesNotContain(bySessionId.getId(), byRoomOnly.getId());
  }

  @Test
  void deleteLegacyDraftsOfSession_Should_matchNoRoomlessDraft_When_theSessionHasNoRoom() {
    var noReference = draft("asker", null, null, null);
    entityManager.flush();

    assertThat(draftMessageRepository.deleteLegacyDraftsOfSession(keptSessionHere.getId(), null))
        .isZero();
    assertThat(draftMessageRepository.findById(noReference.getId())).isPresent();
  }

  @Test
  void countSessionsOfKeptAdviceSeekers_Should_leaveOutThoseDeletedWithTheAgency_BeforeMarking() {
    // The preview runs before the cascade marks anybody: onlyBySession's session goes with the
    // account, so only the sessions of advice seekers who belong elsewhere are counted.
    assertThat(sessionRepository.countSessionsOfKeptAdviceSeekers(AGENCY)).isEqualTo(2L);
  }

  @Test
  void markSessionsOfUserInAgencyForDeletion_Should_markOnlyTheirSessionsHere() {
    int marked =
        sessionRepository.markSessionsOfUserInAgencyForDeletion(
            alsoElsewhereBySession.getUserId(), AGENCY, NOW);
    entityManager.clear();

    assertThat(marked).isEqualTo(1);
    assertThat(deleteDate(keptSessionHere)).isEqualTo(NOW);
    assertThat(deleteDate(keptSessionElsewhere)).isNull();
    assertThat(deleteDate(markedAskersSession)).isNull();
    assertThat(deleteDate(technicalSession)).isNull();
    assertThat(
            sessionRepository.markSessionsOfUserInAgencyForDeletion(
                alsoElsewhereBySession.getUserId(), AGENCY, NOW))
        .isZero();
  }

  @Test
  void deleteMembershipsOfUserInAgency_Should_removeOnlyThisAgencysMembership() {
    int removed =
        userAgencyRepository.deleteMembershipsOfUserInAgency(
            alsoElsewhereByMembership.getUserId(), AGENCY);
    entityManager.clear();

    assertThat(removed).isEqualTo(1);
    assertThat(
            userAgencyRepository.findByUserUserIdIn(
                java.util.List.of(alsoElsewhereByMembership.getUserId())))
        .extracting(UserAgency::getAgencyId)
        .containsExactly(OTHER_AGENCY);
    assertThat(
            userAgencyRepository.findByUserUserIdIn(
                java.util.List.of(onlyByMembership.getUserId())))
        .extracting(UserAgency::getAgencyId)
        .containsExactly(AGENCY);
  }

  @Test
  void deleteLegacyDraftsOfSession_Should_matchTheRoomExactly() {
    var exact = draft("asker", null, "!CaseRoom:matrix", null);
    var otherCase = draft("asker", null, "!caseroom:matrix", null);
    entityManager.flush();

    assertThat(draftMessageRepository.deleteLegacyDraftsOfSession(-1L, "!CaseRoom:matrix"))
        .isEqualTo(1);
    entityManager.flush();
    entityManager.clear();
    assertThat(draftMessageRepository.findById(exact.getId())).isEmpty();
    assertThat(draftMessageRepository.findById(otherCase.getId())).isPresent();
  }

  @Test
  void closeUnassignedEnquiriesOfAgency_Should_closeOnlyThisAgencysUntakenEnquiries() {
    assertThat(sessionRepository.countUnassignedEnquiriesOfAgency(AGENCY)).isEqualTo(1L);

    int closed = sessionRepository.closeUnassignedEnquiriesOfAgency(AGENCY, NOW);
    entityManager.clear();

    assertThat(closed).isEqualTo(1);
    assertThat(sessionRepository.countUnassignedEnquiriesOfAgency(AGENCY)).isZero();
    assertThat(sessionRepository.findById(keptSessionElsewhere.getId()).orElseThrow().getStatus())
        .isEqualTo(SessionStatus.NEW);
    assertThat(sessionRepository.findById(keptSessionHere.getId()).orElseThrow().getStatus())
        .isEqualTo(SessionStatus.IN_PROGRESS);
  }

  @Test
  void closeUnassignedEnquiriesOfAgency_Should_checkTheConsultantAndTheStatusIndependently() {
    var assignedNew = session(asker(), AGENCY, SessionStatus.NEW, counsellor());
    var unassignedInitial = session(asker(), AGENCY, SessionStatus.INITIAL, null);
    entityManager.flush();

    int closed = sessionRepository.closeUnassignedEnquiriesOfAgency(AGENCY, NOW);
    entityManager.clear();

    // The NEW enquiry of onlyBySession and the INITIAL one; a taken enquiry stays open.
    assertThat(closed).isEqualTo(2);
    assertThat(status(assignedNew)).isEqualTo(SessionStatus.NEW);
    assertThat(status(unassignedInitial)).isEqualTo(SessionStatus.DONE);
  }

  @Test
  void counsellorLookups_Should_runTwoStatements_WhateverTheNumberOfCounsellors() {
    var counsellors = new java.util.ArrayList<Consultant>();
    consultantRepository.findAll().forEach(counsellors::add);
    assertThat(counsellors).hasSizeGreaterThanOrEqualTo(3);
    counsellors.subList(0, 3).forEach(counsellor -> relation(counsellor, AGENCY));
    relation(counsellors.get(0), OTHER_AGENCY);
    entityManager.flush();
    entityManager.clear();
    var statistics =
        entityManager
            .getEntityManagerFactory()
            .unwrap(org.hibernate.SessionFactory.class)
            .getStatistics();
    statistics.setStatisticsEnabled(true);
    statistics.clear();

    var ids = consultantAgencyRepository.findActiveConsultantIdsByAgencyId(AGENCY);
    var relations = consultantAgencyRepository.findByConsultantIdInAndDeleteDateIsNull(ids);

    // Id-only projections: no Consultant is loaded, so no select per counsellor.
    assertThat(statistics.getPrepareStatementCount()).isEqualTo(2L);
    assertThat(statistics.getEntityLoadCount()).isZero();
    statistics.setStatisticsEnabled(false);
    assertThat(ids)
        .containsExactlyInAnyOrderElementsOf(
            counsellors.subList(0, 3).stream().map(Consultant::getId).toList());
    assertThat(relations)
        .filteredOn(relation -> relation.getAgencyId() == OTHER_AGENCY)
        .extracting(ConsultantAgency.ConsultantAgencyBase::getConsultantId)
        .containsExactly(counsellors.get(0).getId());
  }

  private void relation(Consultant counsellor, long agencyId) {
    var relation = new ConsultantAgency();
    relation.setConsultant(counsellor);
    relation.setAgencyId(agencyId);
    relation.setTenantId(TENANT);
    entityManager.persist(relation);
  }

  private SessionStatus status(Session session) {
    return sessionRepository.findById(session.getId()).orElseThrow().getStatus();
  }

  private LocalDateTime deleteDate(Session session) {
    return sessionRepository.findById(session.getId()).orElseThrow().getDeleteDate();
  }

  private Consultant counsellor() {
    return consultantRepository.findAll().iterator().next();
  }

  private DraftMessage draft(
      String userId, Long sourceSessionId, String roomRef, String protectedScopeKey) {
    var draft =
        DraftMessage.builder()
            .userId(userId)
            .tenantId(TENANT)
            .scopeKey(protectedScopeKey != null ? protectedScopeKey : UUID.randomUUID().toString())
            .protectedScopeKey(protectedScopeKey)
            .text("synthetic draft")
            .sourceSessionId(sourceSessionId)
            .roomRef(roomRef)
            .createDate(NOW)
            .updateDate(NOW)
            .build();
    entityManager.persist(draft);
    return draft;
  }

  private User asker() {
    return asker(UUID.randomUUID().toString());
  }

  private User asker(String id) {
    var user = new User();
    user.setUserId(id);
    user.setUsername("enc." + id);
    user.setEmail(id + "@example.org");
    user.setTenantId(TENANT);
    user.setEncourage2fa(false);
    user.setMagicLinkLoginEnabled(false);
    user.setLanguageCode(LanguageCode.de);
    entityManager.persist(user);
    return user;
  }

  private void membership(User user, long agencyId) {
    var membership = new UserAgency();
    membership.setUser(user);
    membership.setAgencyId(agencyId);
    entityManager.persist(membership);
  }

  private Session session(User user, long agencyId, SessionStatus status, Consultant consultant) {
    var session = new Session();
    session.setUser(user);
    session.setConsultant(consultant);
    session.setPostcode("12345");
    session.setAgencyId(agencyId);
    session.setStatus(status);
    session.setTenantId(TENANT);
    session.setLanguageCode(LanguageCode.de);
    session.setIsConsultantDirectlySet(false);
    session.setRegistrationType(Session.RegistrationType.REGISTERED);
    entityManager.persist(session);
    return session;
  }
}
