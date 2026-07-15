package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
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
  // listTemplates
  // ---------------------------------------------------------------------------

  @Test
  void listTemplates_Should_returnFindAll_When_kindNull() {
    List<InviteEmailTemplate> all = List.of(InviteEmailTemplate.builder().id(1L).build());
    when(templateRepository.findAll()).thenReturn(all);

    List<InviteEmailTemplate> result = service.listTemplates(null);

    assertThat(result).isSameAs(all);
  }

  @Test
  void listTemplates_Should_filterByKind_When_kindProvided() {
    List<InviteEmailTemplate> filtered = List.of(InviteEmailTemplate.builder().id(2L).build());
    when(templateRepository.findByKindOrderByCreateDateDesc(InviteEmailTemplateKind.TENANT_INVITE))
        .thenReturn(filtered);

    List<InviteEmailTemplate> result = service.listTemplates(InviteEmailTemplateKind.TENANT_INVITE);

    assertThat(result).isSameAs(filtered);
  }
}
