package de.caritas.cob.userservice.api.admin.report.service;

import de.caritas.cob.userservice.agencyadminserivce.generated.web.model.AgencyAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ViolationDTO;
import de.caritas.cob.userservice.api.admin.report.model.ViolationReportRule;
import de.caritas.cob.userservice.api.admin.report.registry.ViolationRuleRegistry;
import de.caritas.cob.userservice.api.admin.service.agency.AgencyAdminService;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Generator for all {@link ViolationReportRule} beans. */
@Service
@RequiredArgsConstructor
public class ViolationReportGenerator {

  private final @NonNull ViolationRuleRegistry violationRuleRegistry;
  private final @NonNull AgencyAdminService agencyAdminService;

  /**
   * Generates a list of all located known violations.
   *
   * @return all found {@link ViolationDTO} objects
   */
  public List<ViolationDTO> generateReport() {
    List<AgencyAdminResponseDTO> allAgencies = this.agencyAdminService.retrieveAllAgencies();
    return this.violationRuleRegistry.getViolationReportRules(allAgencies).stream()
        .map(ViolationReportRule::generateViolations)
        .flatMap(Collection::parallelStream)
        .collect(Collectors.toList());
  }
}
