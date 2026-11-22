package de.caritas.cob.userservice.api.admin.report.rule;

import static de.caritas.cob.userservice.api.adapters.web.dto.ViolationDTO.ViolationTypeEnum.ASKER;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.ViolationDTO;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class MissingSessionAndChatForAskerViolationReportRuleTest {

  @InjectMocks private MissingSessionAndChatForAskerViolationReportRule reportRule;

  @Mock private UserRepository userRepository;

  @Test
  public void generateViolations_Should_returnEmptyList_When_noViolationExists() {
    List<ViolationDTO> violations = this.reportRule.generateViolations();

    assertThat(violations, hasSize(0));
  }

  @Test
  public void generateViolations_Should_returnExpectedViolation_When_oneViolatedAskerExists() {
    when(userRepository.findIdsWithoutSessionOrChatRelations()).thenReturn(List.of("asker-1"));

    List<ViolationDTO> violations = this.reportRule.generateViolations();

    assertThat(violations, hasSize(1));
    ViolationDTO resultViolation = violations.iterator().next();
    assertThat(resultViolation.getIdentifier(), is("asker-1"));
    assertThat(resultViolation.getViolationType(), is(ASKER));
    assertThat(resultViolation.getReason(), is("Use has neither chat nor session relation"));
  }

  @Test
  public void generateViolations_Should_mapAllProjectedIds() {
    when(userRepository.findIdsWithoutSessionOrChatRelations())
        .thenReturn(List.of("asker-1", "asker-2"));

    List<ViolationDTO> violations = this.reportRule.generateViolations();

    assertThat(
        violations.stream().map(ViolationDTO::getIdentifier).toList(),
        contains("asker-1", "asker-2"));
  }
}
