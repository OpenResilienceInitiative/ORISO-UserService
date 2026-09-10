package de.caritas.cob.userservice.api.port.out;

/**
 * One active supervisor of one session, projected to exactly the columns the ADR-008 list marker
 * needs. A scalar projection on purpose: resolving the marker for a whole session list must stay a
 * single query and must not pull the {@code Session} or {@code Consultant} entity graphs.
 *
 * @param sessionId the supervised session
 * @param consultantId keycloak id of the supervising consultant
 * @param username the supervisor's (encoded) username — last-resort display name
 * @param displayName the supervisor's public display name (nullable)
 * @param internalDisplayName the supervisor's internal display name, #996 (nullable)
 */
public record SessionSupervisorMarkerRow(
    Long sessionId,
    String consultantId,
    String username,
    String displayName,
    String internalDisplayName) {}
