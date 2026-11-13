package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.CommunicationStat;
import de.caritas.cob.userservice.api.model.CommunicationStat.Kind;
import de.caritas.cob.userservice.api.model.ConsultantMessageStat;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;

/**
 * Executes every native query of {@link AdminStatisticsRepository} against the H2 testing schema.
 * The goal is SQL syntax and projection-mapping validation; result correctness is covered by the
 * service unit tests.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class AdminStatisticsRepositoryIT {

  private static final long TENANT_ID = 1L;
  private static final LocalDateTime FROM = LocalDateTime.of(2026, 6, 1, 0, 0);
  private static final LocalDateTime TO = LocalDateTime.of(2026, 8, 1, 0, 0);

  @Autowired private AdminStatisticsRepository underTest;
  @Autowired private CommunicationStatRepository communicationStats;
  @Autowired private ConsultantMessageStatRepository messageStats;

  @Test
  void communicationCountsShouldGroupByAgencyKindAndTenant_withinTheMonthOnly() {
    var inMonth = LocalDateTime.of(2026, 7, 10, 9, 0);
    communicationStats.save(row(7L, Kind.ADVICE_SEEKER_MESSAGE, inMonth, null));
    communicationStats.save(row(7L, Kind.ADVICE_SEEKER_MESSAGE, inMonth, null));
    communicationStats.save(row(7L, Kind.VIDEO_CALL, inMonth, "k1"));
    communicationStats.save(row(8L, Kind.AUDIO_CALL, inMonth, "k2"));
    communicationStats.save(row(7L, Kind.ADVICE_SEEKER_MESSAGE, TO, null)); // end is exclusive
    messageStats.save(
        ConsultantMessageStat.builder()
            .consultantHmac("h")
            .tenantId(TENANT_ID)
            .agencyId(7L)
            .sentDate(inMonth)
            .build());

    assertThat(underTest.countCommunicationByAgency(TENANT_ID, FROM, TO))
        .extracting(r -> r.getGroupId() + ":" + r.getKind() + ":" + r.getTotal())
        .containsExactlyInAnyOrder("7:ADVICE_SEEKER_MESSAGE:2", "7:VIDEO_CALL:1", "8:AUDIO_CALL:1");
    assertThat(underTest.countCommunicationByTenant(FROM, TO))
        .extracting(r -> r.getGroupId() + ":" + r.getKind() + ":" + r.getTotal())
        .containsExactlyInAnyOrder(
            TENANT_ID + ":ADVICE_SEEKER_MESSAGE:2",
            TENANT_ID + ":VIDEO_CALL:1",
            TENANT_ID + ":AUDIO_CALL:1");
    assertThat(underTest.countCounsellorMessagesByAgency(TENANT_ID, FROM, TO))
        .extracting(r -> r.getGroupId() + ":" + r.getTotal())
        .containsExactly("7:1");
    assertThat(underTest.countCounsellorMessagesByTenant(FROM, TO))
        .extracting(r -> r.getGroupId() + ":" + r.getTotal())
        .containsExactly(TENANT_ID + ":1");
    assertThat(communicationStats.existsByEventKey("k1")).isTrue();
    assertThat(communicationStats.deleteBatchOccurredBefore(TO, 100)).isEqualTo(4);
  }

  private static CommunicationStat row(
      Long agencyId, Kind kind, LocalDateTime occurredAt, String eventKey) {
    return CommunicationStat.builder()
        .tenantId(TENANT_ID)
        .agencyId(agencyId)
        .sessionId(1L)
        .kind(kind)
        .occurredAt(occurredAt)
        .eventKey(eventKey)
        .build();
  }

  @Test
  void allTenantScopedQueriesShouldExecute() {
    assertThat(underTest.countNewSessionsByAgency(TENANT_ID, FROM, TO)).isEmpty();
    assertThat(underTest.countActiveCasesByAgency(TENANT_ID)).isEmpty();
    assertThat(underTest.countActiveSessionsSinceByAgency(TENANT_ID, FROM)).isEmpty();
    assertThat(underTest.countDailyNewSessionsByAgency(TENANT_ID, FROM)).isEmpty();
    assertThat(underTest.countHourlyNewSessionsByAgency(TENANT_ID, FROM, TO)).isEmpty();
    assertThat(underTest.countSessionTopicsByAgency(TENANT_ID, FROM, TO)).isEmpty();
    assertThat(underTest.countConsultantsByAgency(TENANT_ID)).isEmpty();
    assertThat(underTest.countConsultantsForTenant(TENANT_ID)).isZero();
    assertThat(underTest.countGroupChatsByAgency(TENANT_ID, FROM, TO)).isEmpty();
    assertThat(underTest.countGroupChatsForTenant(TENANT_ID, FROM, TO)).isZero();
    assertThat(underTest.countCounsellorMessagesByAgency(TENANT_ID, FROM, TO)).isEmpty();
    assertThat(underTest.countCommunicationByAgency(TENANT_ID, FROM, TO)).isEmpty();
  }

  @Test
  void allPlatformScopedQueriesShouldExecute() {
    assertThat(underTest.countNewSessionsByTenant(FROM, TO)).isEmpty();
    assertThat(underTest.countActiveCasesByTenant()).isEmpty();
    assertThat(underTest.countActiveSessionsSinceByTenant(FROM)).isEmpty();
    assertThat(underTest.countDailyNewSessionsByTenant(FROM)).isEmpty();
    assertThat(underTest.countHourlyNewSessionsByTenant(FROM, TO)).isEmpty();
    assertThat(underTest.countSessionTopicsByTenant(FROM, TO)).isEmpty();
    assertThat(underTest.countConsultantsByTenant()).isEmpty();
    assertThat(underTest.countGroupChatsByTenant(FROM, TO)).isEmpty();
    assertThat(underTest.countCounsellorMessagesByTenant(FROM, TO)).isEmpty();
    assertThat(underTest.countCommunicationByTenant(FROM, TO)).isEmpty();
  }
}
