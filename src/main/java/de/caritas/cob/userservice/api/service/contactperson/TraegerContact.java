package de.caritas.cob.userservice.api.service.contactperson;

/** The validated Träger default pair and the general contact e-mail of the Träger. */
public record TraegerContact(Long tenantId, ContactPair pair, String generalContactEmail) {}
