package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.conversation.facade.CreateAnonymousEnquiryFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.ConsultingTypeService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Opening hours are stored as written and read back through the public context, against a real
 * database rather than a mocked repository.
 */
@DataJpaTest(properties = "spring.sql.init.mode=never")
@ActiveProfiles("testing")
@Import(AgencyInviteLinkService.class)
class InvitationOpeningHoursDatabaseTest {
  private static final String HOURS =
      "[{\"dayOfWeek\":1,\"opens\":\"09:00\",\"closes\":\"12:00\"},"
          + "{\"dayOfWeek\":1,\"opens\":\"13:00\",\"closes\":\"17:00\"},"
          + "{\"dayOfWeek\":5,\"opens\":\"09:00\",\"closes\":\"11:30\"}]";

  @Autowired AgencyInviteLinkService service;
  @Autowired AgencyInviteLinkRepository links;
  @MockitoBean AuthenticatedUser caller;
  @MockitoBean TopicService topics;
  @MockitoBean ConsultantRepository consultants;
  @MockitoBean ConsultingTypeService consultingTypes;
  @MockitoBean AgencyService agencies;
  @MockitoBean CreateAnonymousEnquiryFacade provisioning;

  @AfterEach
  void cleanup() {
    TenantContext.clear();
    links.deleteAll();
  }

  @Test
  void storedOpeningHoursAndZoneSurviveTheRoundTripThroughTheContext() {
    save("with-hours", HOURS, "Europe/Berlin");

    var context = service.getContext("with-hours");

    assertThat(context.openingHoursTimeZone()).isEqualTo("Europe/Berlin");
    assertThat(context.openingHours())
        .extracting(
            AgencyInviteLinkService.OpeningHoursEntry::dayOfWeek,
            AgencyInviteLinkService.OpeningHoursEntry::opens,
            AgencyInviteLinkService.OpeningHoursEntry::closes)
        .containsExactly(
            tuple(1, "09:00", "12:00"), tuple(1, "13:00", "17:00"), tuple(5, "09:00", "11:30"));
    verifyNoInteractions(provisioning);
  }

  @Test
  void anInvitationWithoutHoursReportsNoneRatherThanClosed() {
    save("no-hours", null, null);

    var context = service.getContext("no-hours");

    assertThat(context.openingHours()).isEmpty();
    assertThat(context.openingHoursTimeZone()).isNull();
  }

  @Test
  void unreadableStoredHoursDoNotBreakAnOtherwiseValidInvitation() {
    save("broken-hours", "{not json at all", "Europe/Berlin");

    var context = service.getContext("broken-hours");

    assertThat(context.openingHours()).isEmpty();
    assertThat(context.tenantId()).isEqualTo(1L);
  }

  private void save(String token, String openingHours, String zone) {
    links.saveAndFlush(
        AgencyInviteLink.builder()
            .token(token)
            .tenantId(1L)
            .consultingTypeId(3)
            .topicId(11L)
            .linkKind("TENANT")
            .chatType("LIVE_CHAT")
            .anonymity("FULL")
            .status("ACTIVE")
            .createdByUserId("test-admin")
            .createDate(LocalDateTime.now())
            .openingHours(openingHours)
            .openingHoursTimeZone(zone)
            .build());
  }
}
