package de.caritas.cob.userservice.api.admin.report.rule;

import static de.caritas.cob.userservice.api.adapters.web.dto.ViolationDTO.ViolationTypeEnum.CONSULTANT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.ViolationDTO;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.service.consultant.ConsultantChatIdentityService;
import java.util.List;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Makes "which counsellors have no chat identity?" answerable from the existing data-integrity
 * report instead of from a database session (#1194).
 */
@ExtendWith(MockitoExtension.class)
class MissingChatIdentityForConsultantViolationReportRuleTest {

  @InjectMocks private MissingChatIdentityForConsultantViolationReportRule reportRule;

  @Mock private ConsultantChatIdentityService consultantChatIdentityService;

  @Test
  void generateViolations_Should_returnEmptyList_When_everyConsultantHasAChatIdentity() {
    when(consultantChatIdentityService.findConsultantsWithoutChatIdentity()).thenReturn(List.of());

    assertThat(this.reportRule.generateViolations(), hasSize(0));
  }

  @Test
  void generateViolations_Should_reportEveryConsultantWithoutAChatIdentity() {
    Consultant violated = new EasyRandom().nextObject(Consultant.class);
    violated.setMatrixUserId(null);
    when(consultantChatIdentityService.findConsultantsWithoutChatIdentity())
        .thenReturn(List.of(violated));

    List<ViolationDTO> violations = this.reportRule.generateViolations();

    assertThat(violations, hasSize(1));
    ViolationDTO violation = violations.iterator().next();
    assertThat(violation.getViolationType(), is(CONSULTANT));
    assertThat(violation.getIdentifier(), is(violated.getId()));
    assertThat(
        violation.getReason(),
        is("Missing chat identity for consultant - cannot be used for counselling"));
    assertThat(violation.getAdditionalInformation(), hasSize(2));
    assertThat(violation.getAdditionalInformation().get(0).getName(), is("Username"));
    assertThat(violation.getAdditionalInformation().get(1).getName(), is("Email"));
  }

  @Test
  void generateViolations_Should_reportOneViolationPerAffectedConsultant() {
    List<Consultant> consultants = new EasyRandom().objects(Consultant.class, 4).toList();
    consultants.forEach(consultant -> consultant.setMatrixUserId(null));
    when(consultantChatIdentityService.findConsultantsWithoutChatIdentity())
        .thenReturn(consultants);

    assertThat(this.reportRule.generateViolations(), hasSize(4));
  }
}
