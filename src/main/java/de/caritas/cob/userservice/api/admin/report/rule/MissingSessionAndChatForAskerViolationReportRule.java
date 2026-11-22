package de.caritas.cob.userservice.api.admin.report.rule;

import static de.caritas.cob.userservice.api.adapters.web.dto.ViolationDTO.ViolationTypeEnum.ASKER;

import de.caritas.cob.userservice.api.adapters.web.dto.ViolationDTO;
import de.caritas.cob.userservice.api.admin.report.model.ViolationReportRule;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.List;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Violation rule to find askers without session or chat relation. */
@Component
@RequiredArgsConstructor
public class MissingSessionAndChatForAskerViolationReportRule implements ViolationReportRule {

  private final @NonNull UserRepository userRepository;

  @Override
  public List<ViolationDTO> generateViolations() {
    return userRepository.findIdsWithoutSessionOrChatRelations().stream()
        .map(this::fromUserId)
        .collect(Collectors.toList());
  }

  private ViolationDTO fromUserId(String userId) {
    return new ViolationDTO()
        .identifier(userId)
        .violationType(ASKER)
        .reason("Use has neither chat nor session relation");
  }
}
