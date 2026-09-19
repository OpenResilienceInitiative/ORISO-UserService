package de.caritas.cob.userservice.api.admin.report.rule;

import de.caritas.cob.userservice.api.adapters.web.dto.ViolationDTO;
import de.caritas.cob.userservice.api.admin.report.builder.ViolationByConsultantBuilder;
import de.caritas.cob.userservice.api.admin.report.model.ViolationReportRule;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.service.consultant.ConsultantChatIdentityService;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Violation rule to find consultants without a chat (Matrix) identity (#1194).
 *
 * <p>Such a consultant was created while the chat server was unreachable. It looks identical to a
 * working one in every admin list, and every counselling room operation refuses it. Reporting it
 * here means the condition can be monitored rather than discovered by a counsellor who cannot start
 * a session.
 */
@Component
@RequiredArgsConstructor
public class MissingChatIdentityForConsultantViolationReportRule implements ViolationReportRule {

  private final @NonNull ConsultantChatIdentityService consultantChatIdentityService;

  /**
   * Generates one violation per {@link Consultant} without a chat identity.
   *
   * @return the generated violations
   */
  @Override
  public List<ViolationDTO> generateViolations() {
    return consultantChatIdentityService.findConsultantsWithoutChatIdentity().stream()
        .map(this::fromConsultant)
        .toList();
  }

  private ViolationDTO fromConsultant(Consultant consultant) {
    return ViolationByConsultantBuilder.getInstance(consultant)
        .withReason("Missing chat identity for consultant - cannot be used for counselling")
        .build();
  }
}
