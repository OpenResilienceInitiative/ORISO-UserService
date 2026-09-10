package de.caritas.cob.userservice.api.adapters.web.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The department coordinate on the live-chat waiting payload.
 *
 * <p>Why it is pinned: the entry room resolves the accepting counselling centre's data-protection
 * declaration from (agencyId, topicId) once the case reads IN_PROGRESS. Drop either half and the
 * consent screen silently falls back to the platform wording — which is not the text that governs
 * the advice seeker, and the failure is invisible because the fallback renders perfectly.
 */
class ConversationDtoMapperTest {

  private final ConversationDtoMapper mapper = new ConversationDtoMapper();

  private static Map<String, Object> sessionMap(Object agencyId, Object mainTopicId) {
    Map<String, Object> map = new HashMap<>();
    map.put("status", "NEW");
    map.put("agencyId", agencyId);
    map.put("mainTopicId", mainTopicId);
    return map;
  }

  @Test
  void anonymousEnquiryOf_should_carryTheDepartmentCoordinate() {
    var enquiry = mapper.anonymousEnquiryOf(sessionMap(17L, 3L), 2, 4L);

    assertThat(enquiry.getAgencyId()).isEqualTo(17L);
    assertThat(enquiry.getMainTopicId()).isEqualTo(3L);
    assertThat(enquiry.getNumAvailableConsultants()).isEqualTo(2);
    assertThat(enquiry.getPeopleAhead()).isEqualTo(4);
  }

  @Test
  void anonymousEnquiryOf_should_widenIntegerIds() {
    // The session map is untyped; JSON and JPA both hand these back as Integer often enough
    // that reading them as Long directly would ClassCastException in production only.
    var enquiry = mapper.anonymousEnquiryOf(sessionMap(17, 3), 0, 0L);

    assertThat(enquiry.getAgencyId()).isEqualTo(17L);
    assertThat(enquiry.getMainTopicId()).isEqualTo(3L);
  }

  @Test
  void anonymousEnquiryOf_should_tolerateAMissingCoordinate() {
    // No agency bound yet, or an enquiry without a topic. The client falls back to the
    // platform wording; the payload must not fail to build.
    var enquiry = mapper.anonymousEnquiryOf(sessionMap(null, null), 0, 0L);

    assertThat(enquiry.getAgencyId()).isNull();
    assertThat(enquiry.getMainTopicId()).isNull();
  }
}
