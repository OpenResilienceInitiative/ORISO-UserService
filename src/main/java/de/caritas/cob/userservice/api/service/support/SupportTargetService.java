package de.caritas.cob.userservice.api.service.support;

import de.caritas.cob.userservice.api.admin.service.admin.GlobalSupportAdminUserService;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The list a Global Support Admin may pick from (ADR-018 §5). One entry per consultant-agency pair,
 * because support is always requested for a consultant at one concrete agency.
 *
 * <p>Deliberately minimal: enough to identify the person and the assignment, and nothing about
 * cases, advice seekers, or availability. Reachable only by a GSA whose profile is ACTIVE and whose
 * second factor is enrolled.
 */
@Service
@RequiredArgsConstructor
public class SupportTargetService {

  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull GlobalSupportAdminUserService globalSupportAdminUserService;

  @Transactional(readOnly = true)
  public Page<SupportTargetItem> search(String infix, Pageable pageable) {
    globalSupportAdminUserService.requireOperationalSupportAdmin();
    return consultantAgencyRepository
        .findSupportTargets(infix == null ? "" : infix, pageable)
        .map(SupportTargetItem::of);
  }

  @Getter
  public static class SupportTargetItem {
    private String consultantId;
    private String firstName;
    private String lastName;
    private String email;
    private Long agencyId;

    static SupportTargetItem of(ConsultantAgency consultantAgency) {
      var consultant = consultantAgency.getConsultant();
      var item = new SupportTargetItem();
      item.consultantId = consultant.getId();
      item.firstName = consultant.getFirstName();
      item.lastName = consultant.getLastName();
      item.email = consultant.getEmail();
      item.agencyId = consultantAgency.getAgencyId();
      return item;
    }
  }
}
