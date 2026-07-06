package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.PublicSlugStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.ReservedPublicSlugRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConsultantPublicSlugService {

  private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z]+(-[a-z]+)*$");

  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ReservedPublicSlugRepository reservedPublicSlugRepository;

  public Optional<Consultant> resolveActiveConsultant(String idOrSlug) {
    if (idOrSlug == null || idOrSlug.isBlank()) {
      return Optional.empty();
    }
    if (isUuid(idOrSlug)) {
      return consultantRepository.findByIdAndDeleteDateIsNull(idOrSlug);
    }
    return consultantRepository.findByPublicSlugAndDeleteDateIsNull(normalize(idOrSlug));
  }

  public void applyAdminSlug(Consultant consultant, String rawSlug) {
    if (rawSlug == null) {
      return;
    }
    if (rawSlug.isBlank()) {
      consultant.setPublicSlug(null);
      consultant.setPendingPublicSlug(null);
      consultant.setPublicSlugStatus(null);
      consultant.setPublicSlugReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
      return;
    }

    var slug = validateAvailableSlug(rawSlug, consultant.getId());
    consultant.setPublicSlug(slug);
    consultant.setPendingPublicSlug(null);
    consultant.setPublicSlugStatus(PublicSlugStatus.APPROVED);
    consultant.setPublicSlugReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
  }

  public void requestSlug(Consultant consultant, String rawSlug) {
    if (rawSlug == null) {
      return;
    }
    if (rawSlug.isBlank()) {
      consultant.setPendingPublicSlug(null);
      consultant.setPublicSlugStatus(null);
      return;
    }

    var slug = validateAvailableSlug(rawSlug, consultant.getId());
    if (slug.equals(consultant.getPublicSlug())) {
      consultant.setPendingPublicSlug(null);
      consultant.setPublicSlugStatus(PublicSlugStatus.APPROVED);
      return;
    }
    consultant.setPendingPublicSlug(slug);
    consultant.setPublicSlugStatus(PublicSlugStatus.PENDING);
    consultant.setPublicSlugReviewedAt(null);
  }

  public void rejectPendingSlug(Consultant consultant) {
    consultant.setPendingPublicSlug(null);
    consultant.setPublicSlugStatus(PublicSlugStatus.REJECTED);
    consultant.setPublicSlugReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
  }

  public String normalize(String rawSlug) {
    return rawSlug == null ? null : rawSlug.trim().toLowerCase();
  }

  private String validateAvailableSlug(String rawSlug, String consultantId) {
    var slug = normalize(rawSlug);
    if (!SLUG_PATTERN.matcher(slug).matches()) {
      throw new BadRequestException(
          "Public slug may only contain lowercase letters separated by single hyphens");
    }
    if (reservedPublicSlugRepository.existsBySlugAndActiveTrue(slug)) {
      throw new BadRequestException("Public slug is reserved");
    }
    if (consultantRepository.existsByPublicSlugAndIdNotAndDeleteDateIsNull(slug, consultantId)
        || consultantRepository.existsByPendingPublicSlugAndIdNotAndDeleteDateIsNull(
            slug, consultantId)) {
      throw new ConflictException("Public slug is already in use");
    }
    return slug;
  }

  private boolean isUuid(String value) {
    try {
      UUID.fromString(value);
      return true;
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }
}
