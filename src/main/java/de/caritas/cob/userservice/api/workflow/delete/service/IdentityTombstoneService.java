package de.caritas.cob.userservice.api.workflow.delete.service;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.IdentityTombstone;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityTombstoneRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IdentityTombstoneService {

  private final @NonNull IdentityTombstoneRepository identityTombstoneRepository;

  public void recordDeletedUser(User user) {
    if (user == null || user.getUserId() == null) {
      return;
    }
    identityTombstoneRepository.save(
        IdentityTombstone.builder()
            .subjectId(user.getUserId())
            .subjectType(IdentityTombstone.SubjectType.USER)
            .displayLabel("Deleted user #" + shortId(user.getUserId()))
            .hardDeletedAt(LocalDateTime.now(ZoneOffset.UTC))
            .sourceDeleteDate(user.getDeleteDate())
            .tenantId(user.getTenantId())
            .build());
  }

  public void recordDeletedConsultant(Consultant consultant) {
    if (consultant == null || consultant.getId() == null) {
      return;
    }
    identityTombstoneRepository.save(
        IdentityTombstone.builder()
            .subjectId(consultant.getId())
            .subjectType(IdentityTombstone.SubjectType.CONSULTANT)
            .displayLabel("Deleted counselor #" + shortId(consultant.getId()))
            .hardDeletedAt(LocalDateTime.now(ZoneOffset.UTC))
            .sourceDeleteDate(consultant.getDeleteDate())
            .tenantId(consultant.getTenantId())
            .build());
  }

  public Optional<String> resolveDisplayLabel(String subjectId) {
    if (subjectId == null || subjectId.isBlank()) {
      return Optional.empty();
    }
    return identityTombstoneRepository
        .findFirstBySubjectIdOrderByHardDeletedAtDesc(subjectId)
        .map(IdentityTombstone::getDisplayLabel);
  }

  private String shortId(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    String sanitized = value.trim();
    return sanitized.length() <= 8 ? sanitized : sanitized.substring(0, 8);
  }
}
