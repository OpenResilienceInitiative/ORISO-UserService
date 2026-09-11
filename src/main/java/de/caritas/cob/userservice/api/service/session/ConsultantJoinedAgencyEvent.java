package de.caritas.cob.userservice.api.service.session;

/**
 * US#1060: a consultant-agency relation was created and committed.
 *
 * <p>Carries ids rather than the {@code Consultant} entity on purpose. The membership fan-out runs
 * after the transaction commits, when the persistence context is gone and the entity is detached —
 * and the fan-out may persist a lazily provisioned Matrix account, so it needs a managed instance
 * it reads itself.
 *
 * @param consultantId the consultant that joined the agency
 * @param agencyId the agency they joined
 */
public record ConsultantJoinedAgencyEvent(String consultantId, Long agencyId) {}
