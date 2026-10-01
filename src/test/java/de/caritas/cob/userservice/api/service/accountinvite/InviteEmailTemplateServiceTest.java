package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateService.TemplateCommand;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InviteEmailTemplateServiceTest {

  @Mock private InviteEmailTemplateRepository templateRepository;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private AccountInviteAccessPolicy accessPolicy;

  @InjectMocks private InviteEmailTemplateService service;

  // ---------------------------------------------------------------------------
  // createTemplate — validation guards
  // ---------------------------------------------------------------------------

  @Test
  void createTemplate_Should_throwBadRequest_When_commandNull() {
    assertThatThrownBy(() -> service.createTemplate(null)).isInstanceOf(BadRequestException.class);
  }

  @Test
  void createTemplate_Should_throwBadRequest_When_kindNull() {
    var command = new TemplateCommand(null, "Name", "en", "Subject", "Body", null);
    assertThatThrownBy(() -> service.createTemplate(command))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void createTemplate_Should_throwBadRequest_When_nameBlank() {
    var command =
        new TemplateCommand(
            InviteEmailTemplateKind.TENANT_INVITE, "  ", "en", "Subject", "Body", null);
    assertThatThrownBy(() -> service.createTemplate(command))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void createTemplate_Should_throwBadRequest_When_subjectBlank() {
    var command =
        new TemplateCommand(
            InviteEmailTemplateKind.TENANT_INVITE, "Name", "en", "  ", "Body", null);
    assertThatThrownBy(() -> service.createTemplate(command))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void createTemplate_Should_throwBadRequest_When_bodyBlank() {
    var command =
        new TemplateCommand(
            InviteEmailTemplateKind.TENANT_INVITE, "Name", "en", "Subject", "  ", null);
    assertThatThrownBy(() -> service.createTemplate(command))
        .isInstanceOf(BadRequestException.class);
  }

  // ---------------------------------------------------------------------------
  // createTemplate — happy paths
  // ---------------------------------------------------------------------------

  @Test
  void createTemplate_Should_defaultActiveToTrue_When_activeNull() {
    when(authenticatedUser.getUserId()).thenReturn("admin-1");
    when(templateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    InviteEmailTemplate result =
        service.createTemplate(
            new TemplateCommand(
                InviteEmailTemplateKind.TENANT_INVITE,
                "  Name  ",
                "  en  ",
                "  Subj  ",
                "Body",
                null));

    assertThat(result.getActive()).isTrue();
    assertThat(result.getName()).isEqualTo("Name");
    assertThat(result.getLanguage()).isEqualTo("en");
    assertThat(result.getSubject()).isEqualTo("Subj");
    assertThat(result.getCreatedByUserId()).isEqualTo("admin-1");
  }

  @Test
  void createTemplate_Should_respectExplicitActiveFalse() {
    when(templateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    InviteEmailTemplate result =
        service.createTemplate(
            new TemplateCommand(
                InviteEmailTemplateKind.COUNSELLOR_INVITE, "Name", "en", "Subj", "Body", false));

    assertThat(result.getActive()).isFalse();
  }

  @Test
  void createTemplate_Should_respectExplicitActiveTrue() {
    when(templateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    InviteEmailTemplate result =
        service.createTemplate(
            new TemplateCommand(
                InviteEmailTemplateKind.COUNSELLOR_INVITE, "Name", "en", "Subj", "Body", true));

    assertThat(result.getActive()).isTrue();
  }

  @Test
  void createTemplate_Should_setLanguageNull_When_languageBlank() {
    when(templateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    InviteEmailTemplate result =
        service.createTemplate(
            new TemplateCommand(
                InviteEmailTemplateKind.TENANT_INVITE, "Name", "   ", "Subj", "Body", null));

    assertThat(result.getLanguage()).isNull();
  }

  // ---------------------------------------------------------------------------
  // updateTemplate
  // ---------------------------------------------------------------------------

  @Test
  void updateTemplate_Should_throwBadRequest_When_commandInvalid() {
    assertThatThrownBy(() -> service.updateTemplate(1L, null))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void updateTemplate_Should_throwNotFound_When_templateMissing() {
    when(templateRepository.findById(99L)).thenReturn(Optional.empty());
    var command =
        new TemplateCommand(
            InviteEmailTemplateKind.TENANT_INVITE, "Name", "en", "Subj", "Body", true);

    assertThatThrownBy(() -> service.updateTemplate(99L, command))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void updateTemplate_Should_updateAllFieldsAndSave_When_templateExists() {
    InviteEmailTemplate existing =
        InviteEmailTemplate.builder()
            .id(1L)
            .kind(InviteEmailTemplateKind.TENANT_INVITE)
            .name("Old")
            .subject("Old subject")
            .body("Old body")
            .active(true)
            .build();
    when(templateRepository.findById(1L)).thenReturn(Optional.of(existing));
    when(templateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    var command =
        new TemplateCommand(
            InviteEmailTemplateKind.COUNSELLOR_INVITE,
            "  New  ",
            "de",
            "  New Subj  ",
            "New body",
            false);

    InviteEmailTemplate result = service.updateTemplate(1L, command);

    assertThat(result.getKind()).isEqualTo(InviteEmailTemplateKind.COUNSELLOR_INVITE);
    assertThat(result.getName()).isEqualTo("New");
    assertThat(result.getLanguage()).isEqualTo("de");
    assertThat(result.getSubject()).isEqualTo("New Subj");
    assertThat(result.getBody()).isEqualTo("New body");
    assertThat(result.getActive()).isFalse();
    verify(templateRepository).save(existing);
  }

  // ---------------------------------------------------------------------------
  // who may write a template (ORISO-Admin#1026 Q30/Q31)
  // ---------------------------------------------------------------------------

  @Test
  void createTemplate_Should_stampTheOwnerFromThePolicy() {
    // Everyone who may send invites may also create a template (Frank, 2026-09-24).
    // Creating is therefore never refused — but the row must carry its owner, or the
    // open door is a cross-Träger write.
    when(accessPolicy.templateOwnerTenantId()).thenReturn(7L);
    when(templateRepository.save(any(InviteEmailTemplate.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    var command =
        new TemplateCommand(
            InviteEmailTemplateKind.TENANT_INVITE, "Name", "en", "Subj", "Body", true);

    assertThat(service.createTemplate(command).getTenantId()).isEqualTo(7L);
  }

  @Test
  void createTemplate_Should_notSave_When_theOwnerCannotBeResolved() {
    // The owner is read BEFORE validation and before the save, so a policy that
    // throws leaves no half-owned row behind.
    when(accessPolicy.templateOwnerTenantId()).thenThrow(new ForbiddenException("denied"));
    var command =
        new TemplateCommand(
            InviteEmailTemplateKind.TENANT_INVITE, "Name", "en", "Subj", "Body", true);

    assertThatThrownBy(() -> service.createTemplate(command))
        .isInstanceOf(ForbiddenException.class);
    verify(templateRepository, never()).save(any());
  }

  @Test
  void updateTemplate_Should_askThePolicyWithTheStoredOwner_And_notSave_When_itDenies() {
    var stored = InviteEmailTemplate.builder().id(1L).tenantId(4L).build();
    when(templateRepository.findById(1L)).thenReturn(Optional.of(stored));
    doThrow(new ForbiddenException("denied")).when(accessPolicy).authorizeTemplateUpdate(4L);
    var command =
        new TemplateCommand(
            InviteEmailTemplateKind.TENANT_INVITE, "Name", "en", "Subj", "Body", true);

    assertThatThrownBy(() -> service.updateTemplate(1L, command))
        .isInstanceOf(ForbiddenException.class);
    verify(templateRepository, never()).save(any());
  }

  @Test
  void requireUsableTemplate_Should_askThePolicyWithTheStoredOwner() {
    // The send path: hiding a foreign template from the list is not enough, because
    // the id travels in the request body.
    var stored = InviteEmailTemplate.builder().id(1L).tenantId(4L).build();
    when(templateRepository.findById(1L)).thenReturn(Optional.of(stored));
    doThrow(new ForbiddenException("denied")).when(accessPolicy).authorizeTemplateUse(4L);

    assertThatThrownBy(() -> service.requireUsableTemplate(1L))
        .isInstanceOf(ForbiddenException.class);
  }

  // ---------------------------------------------------------------------------
  // listTemplates
  // ---------------------------------------------------------------------------

  @Test
  void listTemplates_Should_returnFindAll_When_kindNull() {
    List<InviteEmailTemplate> all = List.of(InviteEmailTemplate.builder().id(1L).build());
    when(accessPolicy.seesEveryTemplate()).thenReturn(true);
    when(templateRepository.findAllVisible(null)).thenReturn(all);

    List<InviteEmailTemplate> result = service.listTemplates(null);

    assertThat(result).isSameAs(all);
  }

  @Test
  void listTemplates_Should_filterByKind_When_kindProvided() {
    List<InviteEmailTemplate> filtered = List.of(InviteEmailTemplate.builder().id(2L).build());
    when(accessPolicy.seesEveryTemplate()).thenReturn(true);
    when(templateRepository.findAllVisible(InviteEmailTemplateKind.TENANT_INVITE))
        .thenReturn(filtered);

    List<InviteEmailTemplate> result = service.listTemplates(InviteEmailTemplateKind.TENANT_INVITE);

    assertThat(result).isSameAs(filtered);
  }

  @Test
  void listTemplates_Should_askOnlyForOwnAndPlatformTemplates_When_callerIsATraeger() {
    List<InviteEmailTemplate> visible = List.of(InviteEmailTemplate.builder().id(3L).build());
    when(accessPolicy.seesEveryTemplate()).thenReturn(false);
    when(accessPolicy.templateOwnerTenantId()).thenReturn(9L);
    when(templateRepository.findVisibleForTenant(InviteEmailTemplateKind.COUNSELLOR_INVITE, 9L))
        .thenReturn(visible);

    assertThat(service.listTemplates(InviteEmailTemplateKind.COUNSELLOR_INVITE)).isSameAs(visible);
    verify(templateRepository, never()).findAllVisible(any());
  }
}
