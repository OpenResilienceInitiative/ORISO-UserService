package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.ViolationDTO;
import de.caritas.cob.userservice.api.admin.report.rule.MissingSessionAndChatForAskerViolationReportRule;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.TenantFilter;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserAgency;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.apache.commons.lang3.RandomStringUtils;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({JpaAuditingConfiguration.class, MissingSessionAndChatForAskerViolationReportRule.class})
class UserRepositoryIT {

  private User user;

  @Autowired private UserRepository userRepository;
  @Autowired private UserAgencyRepository userAgencyRepository;
  @Autowired private MissingSessionAndChatForAskerViolationReportRule integrityRule;
  @Autowired private EntityManager entityManager;

  @AfterEach
  public void reset() {
    if (Objects.nonNull(user)) {
      userRepository.delete(user);
    }
  }

  @Test
  void
      findAllByDeleteDateNullAndNoRunningSessionsAndCreateDateOlderThanShouldReturnUserSubsetOnPastDate() {
    final LocalDateTime dateCheck = LocalDateTime.of(2019, 1, 1, 0, 0, 0, 0);
    final String USER_ID_SHOULD_IN_LIST_1 = "abc9a0a1-c936-45ee-9141-d73dfc0a3999";
    final String USER_ID_SHOULD_IN_LIST_2 = "def9a0a1-c936-45ee-9141-d73dfc0a3888";

    List<User> result =
        userRepository.findAllByDeleteDateNullAndNoRunningSessionsAndCreateDateOlderThan(dateCheck);

    assertThat(result).isNotNull();
    assertThat(result).hasSize(2);
    assertThat(result.stream().map(User::getUserId).anyMatch(USER_ID_SHOULD_IN_LIST_1::equals))
        .isTrue();
    assertThat(result.stream().map(User::getUserId).anyMatch(USER_ID_SHOULD_IN_LIST_2::equals))
        .isTrue();
  }

  @Test
  void
      findAllByDeleteDateNullAndNoRunningSessionsAndCreateDateOlderThanShouldReturnAllUsersOnTomorrowAsDate() {
    var startOfTomorrow = LocalDateTime.now().with(LocalTime.MIDNIGHT).plusDays(1);

    var users =
        userRepository.findAllByDeleteDateNullAndNoRunningSessionsAndCreateDateOlderThan(
            startOfTomorrow);

    assertNotNull(users);
    assertEquals(12, users.size());
    users.stream().forEach(this::assertThatHasNotificationSettings);
  }

  private void assertThatHasNotificationSettings(User user) {
    assertThat(user.isNotificationsEnabled());
    assertThat(user.getNotificationsSettings()).isNull();
  }

  @Test
  void saveShouldWriteAuditingData() {
    givenPersistedUser();

    assertNotNull(user.getCreateDate());
    assertNotNull(user.getUpdateDate());
    assertEquals(user.getCreateDate(), user.getUpdateDate());
  }

  @Test
  void saveShouldWriteDefaultLanguage() {
    givenPersistedUser();

    assertEquals(LanguageCode.de, user.getLanguageCode());
  }

  @Test
  void countActiveAdviceSeekersOfTenant_leavesOutMarkedAndGroupChatSystemUsersAndOtherTenants() {
    var tenantId = 9466L;
    var saved = new ArrayList<User>();
    saved.add(userRepository.save(userIn(tenantId, UUID.randomUUID().toString(), null)));
    saved.add(userRepository.save(userIn(tenantId, UUID.randomUUID().toString(), null)));
    saved.add(
        userRepository.save(
            userIn(tenantId, UUID.randomUUID().toString(), LocalDateTime.now().minusDays(1))));
    saved.add(userRepository.save(userIn(tenantId, "group-chat-system-" + tenantId, null)));
    saved.add(userRepository.save(userIn(tenantId + 1, UUID.randomUUID().toString(), null)));

    try {
      assertEquals(2, userRepository.countActiveAdviceSeekersOfTenant(tenantId));
    } finally {
      userRepository.deleteAll(saved);
    }
  }

  @Test
  void integrityReportProjectsOnlyIdsAndPreservesTheVisibleRelationshipRules() {
    var filter = entityManager.unwrap(org.hibernate.Session.class).enableFilter(TenantFilter.NAME);
    filter.setParameter(TenantFilter.PARAMETER, 0L);
    User withoutRelations = persistAsker(1L, null);
    User deletedWithoutRelations = persistAsker(1L, LocalDateTime.now().minusDays(1));
    User withSession = persistAsker(1L, null);
    User withChat = persistAsker(1L, null);
    User withBoth = persistAsker(1L, null);
    User withLegacySession = persistAsker(1L, null);
    User withForeignSession = persistAsker(1L, null);
    User otherTenant = persistAsker(83L, null);
    User legacyAsker = persistAsker(null, null);
    persistSession(withSession, 1L);
    persistSession(withBoth, 1L);
    persistSession(withLegacySession, null);
    persistSession(withForeignSession, 83L);
    entityManager.persist(new UserAgency(withChat, 10L));
    entityManager.persist(new UserAgency(withBoth, 10L));
    entityManager.flush();
    entityManager.clear();

    var statistics =
        entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    boolean previouslyEnabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    try {
      for (long tenantId : List.of(0L, 1L, 83L)) {
        filter.setParameter(TenantFilter.PARAMETER, tenantId);
        assertIntegrityReportMatchesLegacyRelationshipTraversal();
      }

      filter.setParameter(TenantFilter.PARAMETER, 0L);
      assertThat(reportIds())
          .contains(
              withoutRelations.getUserId(),
              deletedWithoutRelations.getUserId(),
              otherTenant.getUserId(),
              legacyAsker.getUserId())
          .doesNotContain(
              withSession.getUserId(),
              withChat.getUserId(),
              withBoth.getUserId(),
              withLegacySession.getUserId(),
              withForeignSession.getUserId());

      filter.setParameter(TenantFilter.PARAMETER, 1L);
      assertThat(reportIds())
          .contains(withoutRelations.getUserId(), deletedWithoutRelations.getUserId())
          .doesNotContain(
              otherTenant.getUserId(),
              legacyAsker.getUserId(),
              withLegacySession.getUserId(),
              withForeignSession.getUserId());
      entityManager.clear();
      statistics.clear();

      reportIds();

      assertEquals(1L, statistics.getPrepareStatementCount());
      assertEquals(0L, statistics.getEntityLoadCount());
      assertEquals(0L, statistics.getCollectionLoadCount());
    } finally {
      statistics.setStatisticsEnabled(previouslyEnabled);
      entityManager.unwrap(org.hibernate.Session.class).disableFilter(TenantFilter.NAME);
    }
  }

  private void assertIntegrityReportMatchesLegacyRelationshipTraversal() {
    List<String> legacyIds =
        StreamSupport.stream(userRepository.findAll().spliterator(), false)
            .filter(
                user ->
                    user.getSessions().isEmpty() && userAgencyRepository.findByUser(user).isEmpty())
            .map(User::getUserId)
            .toList();
    entityManager.clear();
    assertThat(reportIds()).containsExactlyInAnyOrderElementsOf(legacyIds);
    entityManager.clear();
  }

  private List<String> reportIds() {
    return integrityRule.generateViolations().stream().map(ViolationDTO::getIdentifier).toList();
  }

  private User persistAsker(Long tenantId, LocalDateTime deleteDate) {
    User asker = userIn(tenantId, UUID.randomUUID().toString(), deleteDate);
    entityManager.persist(asker);
    return asker;
  }

  private void persistSession(User asker, Long tenantId) {
    Session session = new Session(asker, 1, "10115", 10L, SessionStatus.IN_ARCHIVE, false);
    session.setTenantId(tenantId);
    session.setLanguageCode(LanguageCode.de);
    session.setIsConsultantDirectlySet(false);
    entityManager.persist(session);
  }

  private static User userIn(Long tenantId, String userId, LocalDateTime deleteDate) {
    var asker =
        new User(
            userId,
            0L,
            RandomStringUtils.randomAlphabetic(20),
            RandomStringUtils.randomAlphabetic(20),
            false);
    asker.setTenantId(tenantId);
    asker.setDeleteDate(deleteDate);
    return asker;
  }

  private void givenPersistedUser() {
    var user =
        new User(
            UUID.randomUUID().toString(),
            0L,
            RandomStringUtils.randomAlphabetic(255),
            RandomStringUtils.randomAlphabetic(255),
            false);
    this.user = userRepository.save(user);
  }
}
