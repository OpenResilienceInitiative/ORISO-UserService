package de.caritas.cob.userservice.api.service.journey;

import static de.caritas.cob.userservice.api.service.journey.JourneyFixtures.link;
import static de.caritas.cob.userservice.api.service.journey.JourneyFixtures.mail;
import static de.caritas.cob.userservice.api.service.journey.JourneyFixtures.phase;
import static de.caritas.cob.userservice.api.service.journey.JourneyFixtures.step;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.model.JourneyRecord;
import de.caritas.cob.userservice.api.port.out.JourneyRecordRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PublicJourneyServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private JourneyRecordRepository repository;
  @Mock private AgencyService agencyService;
  @InjectMocks private PublicJourneyService service;

  @BeforeEach
  void nothingStored() {
    when(repository.findByScopeAndScopeIdOrderByJourneyId(any(), any())).thenReturn(List.of());
    when(repository.findById(any())).thenReturn(Optional.empty());
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private static JourneyRecord row(String scope, long scopeId, String id, Map<String, Object> body)
      throws Exception {
    var row = new JourneyRecord();
    row.setScope(scope);
    row.setScopeId(scopeId);
    row.setJourneyId(id);
    row.setConversationType("AGENCY_COUNSELLING");
    row.setBody(MAPPER.writeValueAsString(body));
    row.setVersion(0L);
    return row;
  }

  private void platform(Map<String, Object>... journeys) throws Exception {
    var rows = new java.util.ArrayList<JourneyRecord>();
    for (var journey : journeys) {
      rows.add(row(JourneyRecord.PLATFORM, 0, (String) journey.get("id"), journey));
    }
    when(repository.findByScopeAndScopeIdOrderByJourneyId(JourneyRecord.PLATFORM, 0L))
        .thenReturn(rows);
  }

  private void layer(String scope, long owner, String journeyId, List<Object> flight)
      throws Exception {
    when(repository.findById(new JourneyRecord.Key(scope, owner, journeyId)))
        .thenReturn(
            Optional.of(row(scope, owner, journeyId, Map.of("phases", Map.of("flight", flight)))));
  }

  @Test
  void answersNothingWhileNoJourneyRegistersTheTopic() throws Exception {
    platform(mail("j1"));
    assertThat(service.forRegistration(99, null)).isEmpty();
  }

  @Test
  void answersTheJourneyThatRegistersTheTopicWithoutWhatOnlyEditorsSee() throws Exception {
    var journey = mail("j1");
    ((Map<String, Object>) phase(journey, "flight").get(0))
        .put("profile", Map.of("author", "Frank"));
    platform(journey);
    var found = service.forRegistration(1, null).orElseThrow();
    assertThat(found.journeyId()).isEqualTo("j1");
    assertThat(found.journey()).doesNotContainKeys("name", "routes");
    assertThat(phase(found.journey(), "flight").get(0).toString()).doesNotContain("profile");
    assertThat(found.link()).isNull();
  }

  @Test
  void aCentreSeesItsTraegersLayerAndThenItsOwn() throws Exception {
    platform(mail("j1"));
    var agency = new AgencyDTO();
    agency.setTenantId(7L);
    when(agencyService.getAgency(42L)).thenReturn(agency);
    layer(JourneyRecord.TENANT, 7, "j1", List.of(step("t:extra", "message", null)));
    var traeger = service.forRegistration(1, 42L).orElseThrow();
    assertThat(phase(traeger.journey(), "flight").toString()).contains("t:extra");

    layer(JourneyRecord.AGENCY, 42, "j1", List.of(step("a:extra", "message", null)));
    var centre = service.forRegistration(1, 42L).orElseThrow();
    assertThat(phase(centre.journey(), "flight").toString())
        .contains("a:extra")
        .doesNotContain("t:extra");
    // The check-in stays the platform's whatever a layer holds.
    assertThat(phase(centre.journey(), "departure")).hasSize(4);
  }

  @Test
  void withoutACentreTheTraegerOfTheDomainApplies() throws Exception {
    platform(mail("j1"));
    layer(JourneyRecord.TENANT, 3, "j1", List.of(step("t:extra", "message", null)));
    TenantContext.setCurrentTenant(3L);
    assertThat(phase(service.forRegistration(1, null).orElseThrow().journey(), "flight").toString())
        .contains("t:extra");
  }

  @Test
  void anUnknownCentreIsNotFound() throws Exception {
    platform(mail("j1"));
    assertThatThrownBy(() -> service.forRegistration(1, 5L))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void answersTheJourneyBehindALinkWithWhereTheLinkLeads() throws Exception {
    var withLink = link("l1", "Kampagne Schulden");
    withLink.put("slug", "schulden");
    withLink.put("agencyName", "Schuldnerberatung Mitte");
    platform(mail("j1"), mail("j2", withLink));
    var found = service.forLink("schulden", null).orElseThrow();
    assertThat(found.journeyId()).isEqualTo("j2");
    assertThat(found.link())
        .containsEntry("slug", "schulden")
        .containsEntry("agencyName", "Schuldnerberatung Mitte")
        .doesNotContainKey("name");
    assertThat(service.forLink("anders", null)).isEmpty();
    assertThat(service.forLink("../etc", null)).isEmpty();
  }
}
