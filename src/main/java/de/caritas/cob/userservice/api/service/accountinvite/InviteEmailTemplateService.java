package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InviteEmailTemplateService {

  private final @NonNull InviteEmailTemplateRepository templateRepository;
  private final @NonNull AuthenticatedUser authenticatedUser;

  @Transactional
  public InviteEmailTemplate createTemplate(TemplateCommand command) {
    validate(command);
    LocalDateTime now = LocalDateTime.now();
    InviteEmailTemplate template =
        InviteEmailTemplate.builder()
            .kind(command.kind())
            .name(command.name().trim())
            .language(trimToNull(command.language()))
            .subject(command.subject().trim())
            .body(command.body())
            .active(command.active() == null || command.active())
            .createdByUserId(authenticatedUser.getUserId())
            .createDate(now)
            .updateDate(now)
            .build();
    return templateRepository.save(template);
  }

  @Transactional
  public InviteEmailTemplate updateTemplate(Long templateId, TemplateCommand command) {
    validate(command);
    InviteEmailTemplate template =
        templateRepository
            .findById(templateId)
            .orElseThrow(() -> new NotFoundException("Invite e-mail template not found"));
    template.setKind(command.kind());
    template.setName(command.name().trim());
    template.setLanguage(trimToNull(command.language()));
    template.setSubject(command.subject().trim());
    template.setBody(command.body());
    template.setActive(command.active() == null || command.active());
    template.setUpdateDate(LocalDateTime.now());
    return templateRepository.save(template);
  }

  @Transactional(readOnly = true)
  public List<InviteEmailTemplate> listTemplates(InviteEmailTemplateKind kind) {
    if (kind == null) {
      return templateRepository.findAll();
    }
    return templateRepository.findByKindOrderByCreateDateDesc(kind);
  }

  private static void validate(TemplateCommand command) {
    if (command == null) {
      throw new BadRequestException("Request body is required");
    }
    if (command.kind() == null) {
      throw new BadRequestException("kind is required");
    }
    if (isBlank(command.name())) {
      throw new BadRequestException("name is required");
    }
    if (isBlank(command.subject())) {
      throw new BadRequestException("subject is required");
    }
    if (isBlank(command.body())) {
      throw new BadRequestException("body is required");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static String trimToNull(String value) {
    return isBlank(value) ? null : value.trim();
  }

  public record TemplateCommand(
      InviteEmailTemplateKind kind,
      String name,
      String language,
      String subject,
      String body,
      Boolean active) {}
}
