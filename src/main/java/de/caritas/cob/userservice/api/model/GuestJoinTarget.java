package de.caritas.cob.userservice.api.model;

/** Server-resolved invitation context; never supplied directly by the public caller. */
public record GuestJoinTarget(
    Long inviteLinkId, Long tenantId, Long topicId, Integer consultingTypeId) {}
