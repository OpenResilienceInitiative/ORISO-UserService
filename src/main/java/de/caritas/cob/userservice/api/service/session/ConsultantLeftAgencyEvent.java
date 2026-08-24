package de.caritas.cob.userservice.api.service.session;

/**
 * US#1060: a consultant-agency relation was marked deleted and committed. See {@link
 * ConsultantJoinedAgencyEvent} for why this carries ids instead of the entity.
 *
 * @param consultantId the consultant that left the agency
 * @param agencyId the agency they left
 */
public record ConsultantLeftAgencyEvent(String consultantId, Long agencyId) {}
