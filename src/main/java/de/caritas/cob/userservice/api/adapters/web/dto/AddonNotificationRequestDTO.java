package de.caritas.cob.userservice.api.adapters.web.dto;

import java.util.Map;

public record AddonNotificationRequestDTO(
    String userId,
    Long tenantId,
    String addon,
    String appId,
    String slot,
    Long sessionId,
    Boolean important,
    Map<String, String> title,
    Map<String, String> text) {}
