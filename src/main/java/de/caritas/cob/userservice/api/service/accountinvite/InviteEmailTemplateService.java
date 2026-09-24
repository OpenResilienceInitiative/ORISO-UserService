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
  private final @NonNull AccountInviteAccessPolicy accessPolicy;

  @Transactional
  public InviteEmailTemplate createTemplate(TemplateCommand command) {
    // Creating is open to every admin who may send invites (ORISO-Admin#1026 Q30/Q31).
    // What makes that safe is the owner stamped here: a Träger's template belongs to
    // that Träger and is invisible to the others. The platform admin writes templates
    // with no owner, which everyone may use but only the platform admin may change.
    Long ownerTenantId = accessPolicy.templateOwnerTenantId();
    validate(command);
    LocalDateTime now = LocalDateTime.now();
    InviteEmailTemplate template =
        InviteEmailTemplate.builder()
            .tenantId(ownerTenantId)
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
    // After the load, because the answer depends on who owns the row. A template of
    // another Träger is refused as 403, not silently reported as missing, so the Admin
    // can tell "not yours" from "gone".
    accessPolicy.authorizeTemplateUpdate(template.getTenantId());
    template.setKind(command.kind());
    template.setName(command.name().trim());
    template.setLanguage(trimToNull(command.language()));
    template.setSubject(command.subject().trim());
    template.setBody(command.body());
    template.setActive(command.active() == null || command.active());
    template.setUpdateDate(LocalDateTime.now());
    return templateRepository.save(template);
  }

  /** Own templates plus the platform's; the platform admin sees every Träger's. */
  @Transactional(readOnly = true)
  public List<InviteEmailTemplate> listTemplates(InviteEmailTemplateKind kind) {
    if (accessPolicy.seesEveryTemplate()) {
      return templateRepository.findAllVisible(kind);
    }
    return templateRepository.findVisibleForTenant(kind, accessPolicy.templateOwnerTenantId());
  }

  /**
   * Whether the caller may change this stored template — what the API reports as {@code editable}.
   */
  public boolean mayChange(InviteEmailTemplate template) {
    return template != null && accessPolicy.canChangeTemplate(template.getTenantId());
  }

  /**
   * Loads a template for sending or previewing and refuses another Träger's.
   *
   * <p>Hiding a foreign template from the list is not enough: the id travels in the send request
   * body, so an admin who learns one could otherwise send with it.
   */
  @Transactional(readOnly = true)
  public InviteEmailTemplate requireUsableTemplate(Long templateId) {
    if (templateId == null) {
      throw new BadRequestException("templateId is required");
    }
    InviteEmailTemplate template =
        templateRepository
            .findById(templateId)
            .orElseThrow(() -> new NotFoundException("Invite e-mail template not found"));
    accessPolicy.authorizeTemplateUse(template.getTenantId());
    return template;
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
