package de.caritas.cob.userservice.api.picture;

import jakarta.validation.constraints.NotNull;

/**
 * Issue #1049: the counsellor's publish decision for their stored picture. {@code true} keeps the
 * picture internal to colleagues and administrators, {@code false} also shows it to advice seekers.
 */
public record ConsultantPictureVisibility(@NotNull Boolean internalOnly) {}
