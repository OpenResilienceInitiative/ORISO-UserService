package de.caritas.cob.userservice.api.service.erstantwort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.model.JourneyRecord;
import de.caritas.cob.userservice.api.port.out.JourneyRecordRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.journey.PublicJourneyService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * ADR-036 decision 42 with the real {@link PublicJourneyService}: only a <em>stored</em> journey
 * that registers the enquiry's own topic changes the first reply. Dev starts with no stored journey
 * (the Admin tile shows an unsaved browser default, ORISO-Admin#1661), so every topic must stay on
 * version 1 until an editor saves.
 */
class JourneyFirstReplyStoredJourneyTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final long U25_TOPIC = 3L;
  private static final long OTHER_TOPIC = 1L;
  private static final long AGENCY = 7L;
  private static final long TENANT = 1L;

  private final JourneyRecordRepository repository = mock(JourneyRecordRepository.class);
  private final AgencyService agencyService = mock(AgencyService.class);
  private final JourneyFirstReplyBuilder builder =
      new JourneyFirstReplyBuilder(
          new PublicJourneyService(repository, agencyService), new ErstantwortPayloadBuilder());

  private final ErstantwortContext context =
      ErstantwortContext.builder()
          .modality(ErstantwortModality.AGENCY_COUNSELLING)
          .locale("de")
          .build();

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(builder, "enabled", true);
    when(repository.findByScopeAndScopeIdOrderByJourneyId(any(), any())).thenReturn(List.of());
    when(repository.findById(any())).thenReturn(Optional.empty());
    var agency = new AgencyDTO();
    agency.setId(AGENCY);
    agency.setTenantId(TENANT);
    agency.setOffline(false);
    when(agencyService.getAgencyWithoutCaching(AGENCY)).thenReturn(agency);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private void storePlatformJourney(Map<String, Object> journey) throws Exception {
    var row = new JourneyRecord();
    row.setScope(JourneyRecord.PLATFORM);
    row.setScopeId(0L);
    row.setJourneyId((String) journey.get("id"));
    row.setConversationType("AGENCY_COUNSELLING");
    row.setBody(MAPPER.writeValueAsString(journey));
    row.setVersion(0L);
    when(repository.findByScopeAndScopeIdOrderByJourneyId(JourneyRecord.PLATFORM, 0L))
        .thenReturn(List.of(row));
  }

  @Test
  void nothingStoredKeepsEveryTopicOnVersionOne() {
    assertThat(builder.buildFor(U25_TOPIC, AGENCY, context)).isEmpty();
    assertThat(builder.buildFor(OTHER_TOPIC, AGENCY, context)).isEmpty();
  }

  @Test
  void aStoredJourneyChangesOnlyTheTopicItRegisters() throws Exception {
    storePlatformJourney(
        Map.of(
            "id",
            "u25",
            "conversationType",
            "AGENCY_COUNSELLING",
            "routes",
            Map.of("registration", List.of(U25_TOPIC), "links", List.of()),
            "phases",
            Map.of(
                "flight",
                List.of(
                    Map.of(
                        "id",
                        "u25:box",
                        "kind",
                        "panel",
                        "aspects",
                        List.of(
                            Map.of(
                                "id",
                                "u25:box:1",
                                "title",
                                Map.of("de", "Schön, dass Du da bist"),
                                "body",
                                Map.of("de", "<p>Hallo</p>"))))))));

    var u25 = builder.buildFor(U25_TOPIC, AGENCY, context);
    assertThat(u25).isPresent();
    assertThat(u25.get()).contains("\"version\":2").contains("Schön, dass Du da bist");
    // A topic the stored journey does not register keeps today's first reply.
    assertThat(builder.buildFor(OTHER_TOPIC, AGENCY, context)).isEmpty();
  }
}
