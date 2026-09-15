package de.caritas.cob.userservice.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.caritas.cob.userservice.api.model.CaseHandoverConsentMode;
import de.caritas.cob.userservice.api.service.CaseHandoverService.CaseHandoverReason;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wire-shape regression for the Admin "Fallübergabe" card (UserService #1131). Spring MVC
 * deserializes with Jackson 3, so the test uses the same mapper family as the runtime.
 */
class CaseHandoverReasonJsonTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void acceptsTypedConsentPolicyObjectSentByAdmin() {
    String adminPayload =
        "{\"code\":\"COUNSELLOR_ASKED_FOR_ADVICE\",\"label\":\"Counsellor asked for advice\","
            + "\"clientConsent\":{\"value\":\"OPT_IN\",\"mode\":\"SUGGESTED\"},"
            + "\"clientConsentRequired\":true,\"accessAllowed\":true,\"enabled\":true,"
            + "\"displayOrder\":10,\"maxAccessDurationMinutes\":180,\"approvalRoles\":null,"
            + "\"clientNotificationTemplates\":null,"
            + "\"policyAuthority\":\"platform-admin-default-case-handover-policy\"}";

    CaseHandoverReason reason = MAPPER.readValue(adminPayload, CaseHandoverReason.class);

    assertEquals(CaseHandoverConsentMode.OPT_IN, reason.getClientConsent());
    assertEquals("SUGGESTED", reason.getClientConsentMode());
    assertTrue(reason.isClientConsentRequired());
    assertEquals(180, reason.getMaxAccessDurationMinutes());
  }

  @Test
  void acceptsBareConsentModeStringSentByFrontendAndOwnResponses() {
    String payload =
        "{\"code\":\"COUNSELLOR_ON_HOLIDAY\",\"label\":\"x\",\"clientConsent\":\"NONE\"}";

    CaseHandoverReason reason = MAPPER.readValue(payload, CaseHandoverReason.class);

    assertEquals(CaseHandoverConsentMode.NONE, reason.getClientConsent());
    assertNull(reason.getClientConsentMode());
  }

  @Test
  void acceptsMixedShapesInOneList() {
    String payload =
        "[{\"code\":\"A\",\"label\":\"a\",\"clientConsent\":{\"value\":\"OPT_OUT\",\"mode\":\"ENFORCED\"}},"
            + "{\"code\":\"B\",\"label\":\"b\",\"clientConsent\":\"NONE\"},"
            + "{\"code\":\"C\",\"label\":\"c\",\"clientConsent\":null}]";

    List<CaseHandoverReason> reasons =
        MAPPER.readValue(
            payload,
            MAPPER.getTypeFactory().constructCollectionType(List.class, CaseHandoverReason.class));

    assertEquals(CaseHandoverConsentMode.OPT_OUT, reasons.get(0).getClientConsent());
    assertEquals("ENFORCED", reasons.get(0).getClientConsentMode());
    assertEquals(CaseHandoverConsentMode.NONE, reasons.get(1).getClientConsent());
    assertNull(reasons.get(2).getClientConsent());
  }

  @Test
  void rejectsUnknownConsentValue() {
    String payload = "{\"code\":\"A\",\"label\":\"a\",\"clientConsent\":{\"value\":\"MAYBE\"}}";

    assertThrows(RuntimeException.class, () -> MAPPER.readValue(payload, CaseHandoverReason.class));
  }

  @Test
  void responseKeepsEmittingTheBareString() {
    CaseHandoverReason reason =
        CaseHandoverReason.builder()
            .code("COUNSELLOR_ASKED_FOR_ADVICE")
            .label("x")
            .clientConsent(CaseHandoverConsentMode.OPT_OUT)
            .clientConsentMode("SUGGESTED")
            .build();

    String json = MAPPER.writeValueAsString(reason);

    assertTrue(json.contains("\"clientConsent\":\"OPT_OUT\""), json);
    assertTrue(json.contains("\"clientConsentMode\":\"SUGGESTED\""), json);
  }
}
