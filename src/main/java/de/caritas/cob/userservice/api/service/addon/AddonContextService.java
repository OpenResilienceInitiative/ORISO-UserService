package de.caritas.cob.userservice.api.service.addon;

import de.caritas.cob.userservice.api.adapters.web.dto.AddonContextDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AddonContextDTO.ConsultantRef;
import de.caritas.cob.userservice.api.adapters.web.dto.AddonContextDTO.SessionRef;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only context of a user for add-ons (contract oriso-addon/v1, ADR-034): role, agencies,
 * sessions and the consultants involved. Called by the AddonService only, scoped to one tenant.
 */
@Service
@RequiredArgsConstructor
public class AddonContextService {

  static final int SESSION_PAGE_SIZE = 500;
  static final String ASKER = "asker";
  static final String CONSULTANT = "consultant";

  private static final Long LEGACY_TENANT = 1L;

  private final @NonNull UserRepository userRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull UsernameTranscoder usernameTranscoder;

  @Transactional(readOnly = true)
  public AddonContextDTO contextOf(String userId, Long tenantId) {
    if (userId == null || userId.isBlank()) {
      throw new BadRequestException("userId is missing");
    }
    if (tenantId == null || tenantId <= 0) {
      throw new BadRequestException("tenantId must be a positive number");
    }
    var asker = userRepository.findByUserIdAndDeleteDateIsNull(userId);
    if (asker.isPresent()) {
      requireTenant(asker.get().getTenantId(), tenantId);
      return askerContext(userId, tenantId);
    }
    var consultant = consultantRepository.findByIdAndDeleteDateIsNull(userId);
    if (consultant.isPresent()) {
      requireTenant(consultant.get().getTenantId(), tenantId);
      return consultantContext(consultant.get(), tenantId);
    }
    throw new NotFoundException("User not found");
  }

  private AddonContextDTO askerContext(String userId, Long tenantId) {
    var sessions =
        sessionRepository.findByUserUserId(userId).stream()
            .filter(session -> inTenant(session.getTenantId(), tenantId))
            .sorted(Comparator.comparing(Session::getId))
            .toList();
    var agencyIds =
        sessions.stream().map(Session::getAgencyId).filter(Objects::nonNull).distinct().toList();
    var consultants = new LinkedHashMap<String, Consultant>();
    sessions.stream()
        .map(Session::getConsultant)
        .filter(Objects::nonNull)
        .forEach(consultant -> consultants.putIfAbsent(consultant.getId(), consultant));
    return new AddonContextDTO(
        userId,
        ASKER,
        agencyIds,
        sessions.stream().map(AddonContextService::sessionRef).toList(),
        consultants.values().stream().map(this::consultantRef).toList());
  }

  private AddonContextDTO consultantContext(Consultant consultant, Long tenantId) {
    var agencyIds =
        consultantAgencyRepository
            .findByConsultantIdAndDeleteDateIsNull(consultant.getId())
            .stream()
            .map(ConsultantAgency::getAgencyId)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
    var sessions =
        allSessionsOf(consultant.getId()).stream()
            .filter(session -> inTenant(session.getTenantId(), tenantId))
            .map(AddonContextService::sessionRef)
            .toList();
    return new AddonContextDTO(
        consultant.getId(), CONSULTANT, agencyIds, sessions, List.of(consultantRef(consultant)));
  }

  /** Every session of the consultant, newest first, read in pages so none is left out. */
  private List<Session> allSessionsOf(String consultantId) {
    var sessions = new ArrayList<Session>();
    Pageable page =
        PageRequest.of(
            0, SESSION_PAGE_SIZE, Sort.by(Sort.Order.desc("createDate"), Sort.Order.desc("id")));
    while (true) {
      var result = sessionRepository.findByConsultantId(consultantId, page);
      sessions.addAll(result.getContent());
      if (!result.hasNext()) {
        return sessions;
      }
      page = result.nextPageable();
    }
  }

  private static SessionRef sessionRef(Session session) {
    var consultant = session.getConsultant();
    return new SessionRef(
        session.getId(),
        session.getAgencyId(),
        consultant == null ? null : consultant.getId(),
        session.getStatus() == null ? null : session.getStatus().name());
  }

  private ConsultantRef consultantRef(Consultant consultant) {
    var username =
        consultant.getUsername() == null
            ? null
            : usernameTranscoder.decodeUsername(consultant.getUsername());
    var realName =
        Stream.of(consultant.getFirstName(), consultant.getLastName())
            .filter(part -> part != null && !part.isBlank())
            .map(String::trim)
            .collect(Collectors.joining(" "));
    return new ConsultantRef(
        consultant.getId(), username, realName.isEmpty() ? username : realName);
  }

  private static void requireTenant(Long userTenantId, Long requestedTenantId) {
    if (!inTenant(userTenantId, requestedTenantId)) {
      // Not 403: the caller must not learn that the user exists in another tenant.
      throw new NotFoundException("User not found");
    }
  }

  private static boolean inTenant(Long rowTenantId, Long requestedTenantId) {
    var effective = rowTenantId == null ? LEGACY_TENANT : rowTenantId;
    return Objects.equals(effective, requestedTenantId);
  }
}
