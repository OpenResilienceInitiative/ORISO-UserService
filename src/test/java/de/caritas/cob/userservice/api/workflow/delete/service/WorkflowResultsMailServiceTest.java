package de.caritas.cob.userservice.api.workflow.delete.service;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.ROCKET_CHAT;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.service.helper.MailService;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowInfo;
import de.caritas.cob.userservice.mailservice.generated.web.model.ErrorMailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

@RunWith(MockitoJUnitRunner.class)
public class WorkflowResultsMailServiceTest {

  @InjectMocks private WorkflowResultsMailService workflowResultsMailService;

  @Mock private MailService mailService;

  @Mock private TenantTemplateSupplier tenantTemplateSupplier;

  @Before
  public void setup() {
    setField(workflowResultsMailService, "applicationBaseUrl", "www.host.de");
    setField(workflowResultsMailService, "multitenancyEnabled", false);
  }

  @Test
  public void buildAndSendErrorMail_Should_sendMail_When_workflowErrorsAreNull() {
    this.workflowResultsMailService.buildAndSendErrorMail(null);

    verify(this.mailService, times(1)).sendErrorEmailNotification(any());
  }

  @Test
  public void buildAndSendErrorMail_Should_sendMail_When_workflowErrorsAreEmpty() {
    this.workflowResultsMailService.buildAndSendErrorMail(emptyList());

    verify(this.mailService, times(1)).sendErrorEmailNotification(any());
  }

  @Test
  public void
      buildAndSendErrorMail_Should_buildAndSendExpectedErrorMail_When_workflowErrorsExists() {
    // given
    ReflectionTestUtils.setField(workflowResultsMailService, "multitenancyEnabled", true);
    TemplateDataDTO tenantData = new TemplateDataDTO().key("tenantData");
    when(tenantTemplateSupplier.getTemplateAttributes()).thenReturn(Lists.newArrayList(tenantData));
    List<DeletionWorkflowError> workflowErrors =
        asList(
            DeletionWorkflowError.builder()
                .deletionSourceType(ASKER)
                .deletionTargetType(ROCKET_CHAT)
                .timestamp(nowInUtc())
                .reason("reason")
                .identifier("id")
                .build(),
            DeletionWorkflowError.builder().build());

    // when
    this.workflowResultsMailService.buildAndSendErrorMail(workflowErrors);

    // then
    ArgumentCaptor<ErrorMailDTO> errorMailDTOArgumentCaptor =
        ArgumentCaptor.forClass(ErrorMailDTO.class);
    verify(this.mailService, times(1))
        .sendErrorEmailNotification(errorMailDTOArgumentCaptor.capture());

    var templateData = errorMailDTOArgumentCaptor.getValue().getTemplateData();
    assertThat(templateData).contains(tenantData);

    // clean up
    ReflectionTestUtils.setField(workflowResultsMailService, "multitenancyEnabled", false);
  }

  @Test
  public void
      buildAndSendErrorMailWithInfo_Should_sendEmail_withEmptyResultsText_When_bothErrorsAndInfoAreEmpty() {
    this.workflowResultsMailService.buildAndSendMail(emptyList(), emptyList());

    ArgumentCaptor<ErrorMailDTO> errorMailDTOArgumentCaptor =
        ArgumentCaptor.forClass(ErrorMailDTO.class);
    verify(this.mailService, times(1))
        .sendErrorEmailNotification(errorMailDTOArgumentCaptor.capture());

    var templateData = errorMailDTOArgumentCaptor.getValue().getTemplateData();
    TemplateDataDTO textData =
        templateData.stream().filter(t -> "text".equals(t.getKey())).findFirst().orElse(null);
    assertThat(textData).isNotNull();
    assertThat(textData.getValue()).contains("No deletion info");
    assertThat(textData.getValue()).contains("No errors occurred");
  }

  @Test
  public void buildAndSendErrorMailWithInfo_Should_sendMail_When_bothErrorsAndInfoAreNull() {
    this.workflowResultsMailService.buildAndSendMail(null, null);

    ArgumentCaptor<ErrorMailDTO> errorMailDTOArgumentCaptor =
        ArgumentCaptor.forClass(ErrorMailDTO.class);
    verify(this.mailService, times(1))
        .sendErrorEmailNotification(errorMailDTOArgumentCaptor.capture());

    var templateData = errorMailDTOArgumentCaptor.getValue().getTemplateData();
    TemplateDataDTO textData =
        templateData.stream().filter(t -> "text".equals(t.getKey())).findFirst().orElse(null);
    assertThat(textData).isNotNull();
    assertThat(textData.getValue()).contains("No deletion info");
    assertThat(textData.getValue()).contains("No errors occurred");
  }

  @Test
  public void buildAndSendErrorMailWithInfo_Should_sendMailExists() {
    // given
    ReflectionTestUtils.setField(workflowResultsMailService, "multitenancyEnabled", false);
    Date lastMessageDate = new Date();
    List<DeletionWorkflowInfo> deletionInfo =
        asList(
            DeletionWorkflowInfo.builder()
                .userId("user123")
                .userName("testUser")
                .lastMessageDate(lastMessageDate)
                .build());

    // when
    this.workflowResultsMailService.buildAndSendMail(emptyList(), deletionInfo);

    // then
    ArgumentCaptor<ErrorMailDTO> errorMailDTOArgumentCaptor =
        ArgumentCaptor.forClass(ErrorMailDTO.class);
    verify(this.mailService, times(1))
        .sendErrorEmailNotification(errorMailDTOArgumentCaptor.capture());

    var templateData = errorMailDTOArgumentCaptor.getValue().getTemplateData();
    TemplateDataDTO subjectData =
        templateData.stream().filter(t -> "subject".equals(t.getKey())).findFirst().orElse(null);
    assertThat(subjectData).isNotNull();
    assertThat(subjectData.getValue()).isEqualTo("Deletion workflow report");

    TemplateDataDTO textData =
        templateData.stream().filter(t -> "text".equals(t.getKey())).findFirst().orElse(null);
    assertThat(textData).isNotNull();
    assertThat(textData.getValue()).contains("Successfully deleted users:");
    assertThat(textData.getValue()).contains("user123");
    assertThat(textData.getValue()).contains("testUser");
  }

  @Test
  public void buildAndSendErrorMailWithInfo_Should_sendMailWithErrorsOnly_When_onlyErrorsExist() {
    // given
    ReflectionTestUtils.setField(workflowResultsMailService, "multitenancyEnabled", false);
    List<DeletionWorkflowError> workflowErrors =
        asList(
            DeletionWorkflowError.builder()
                .deletionSourceType(ASKER)
                .deletionTargetType(ROCKET_CHAT)
                .timestamp(nowInUtc())
                .reason("test reason")
                .identifier("id123")
                .build());

    // when
    this.workflowResultsMailService.buildAndSendMail(workflowErrors, emptyList());

    // then
    ArgumentCaptor<ErrorMailDTO> errorMailDTOArgumentCaptor =
        ArgumentCaptor.forClass(ErrorMailDTO.class);
    verify(this.mailService, times(1))
        .sendErrorEmailNotification(errorMailDTOArgumentCaptor.capture());

    var templateData = errorMailDTOArgumentCaptor.getValue().getTemplateData();
    TemplateDataDTO subjectData =
        templateData.stream().filter(t -> "subject".equals(t.getKey())).findFirst().orElse(null);
    assertThat(subjectData).isNotNull();
    assertThat(subjectData.getValue()).isEqualTo("Deletion workflow report");

    TemplateDataDTO textData =
        templateData.stream().filter(t -> "text".equals(t.getKey())).findFirst().orElse(null);
    assertThat(textData).isNotNull();
    assertThat(textData.getValue()).contains("Errors during deletion workflow");
    assertThat(textData.getValue()).contains("test reason");
    assertThat(textData.getValue()).contains("id123");
  }

  @Test
  public void buildAndSendErrorMailWithInfo_Should_sendMailWithBothInfoAndErrors_When_bothExist() {
    // given
    ReflectionTestUtils.setField(workflowResultsMailService, "multitenancyEnabled", false);
    Date lastMessageDate = new Date();
    List<DeletionWorkflowInfo> deletionInfo =
        asList(
            DeletionWorkflowInfo.builder()
                .userId("user123")
                .userName("testUser")
                .lastMessageDate(lastMessageDate)
                .build());
    List<DeletionWorkflowError> workflowErrors =
        asList(
            DeletionWorkflowError.builder()
                .deletionSourceType(ASKER)
                .deletionTargetType(ROCKET_CHAT)
                .timestamp(nowInUtc())
                .reason("error reason")
                .identifier("errorId")
                .build());

    // when
    this.workflowResultsMailService.buildAndSendMail(workflowErrors, deletionInfo);

    // then
    ArgumentCaptor<ErrorMailDTO> errorMailDTOArgumentCaptor =
        ArgumentCaptor.forClass(ErrorMailDTO.class);
    verify(this.mailService, times(1))
        .sendErrorEmailNotification(errorMailDTOArgumentCaptor.capture());

    var templateData = errorMailDTOArgumentCaptor.getValue().getTemplateData();
    TemplateDataDTO subjectData =
        templateData.stream().filter(t -> "subject".equals(t.getKey())).findFirst().orElse(null);
    assertThat(subjectData).isNotNull();
    assertThat(subjectData.getValue()).isEqualTo("Deletion workflow report");

    TemplateDataDTO textData =
        templateData.stream().filter(t -> "text".equals(t.getKey())).findFirst().orElse(null);
    assertThat(textData).isNotNull();
    // Verify info section comes first
    assertThat(textData.getValue()).contains("Successfully deleted users:");
    assertThat(textData.getValue()).contains("user123");
    assertThat(textData.getValue()).contains("testUser");
    // Verify errors section
    assertThat(textData.getValue()).contains("Errors during deletion workflow");
    assertThat(textData.getValue()).contains("error reason");
    assertThat(textData.getValue()).contains("errorId");
  }

  @Test
  public void buildAndSendMail_Should_includeLastMessageDateInEmail() {
    // given
    ReflectionTestUtils.setField(workflowResultsMailService, "multitenancyEnabled", false);
    Date lastMessageDate = new Date(1700000000000L); // Fixed date for testing
    String expectedLastMessageDate =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .format(LocalDateTime.ofInstant(lastMessageDate.toInstant(), ZoneOffset.UTC));
    List<DeletionWorkflowInfo> deletionInfo =
        Collections.singletonList(
            DeletionWorkflowInfo.builder()
                .userId("user123")
                .userName("testUser")
                .lastMessageDate(lastMessageDate)
                .build());

    // when
    this.workflowResultsMailService.buildAndSendMail(null, deletionInfo);

    // then
    ArgumentCaptor<ErrorMailDTO> errorMailDTOArgumentCaptor =
        ArgumentCaptor.forClass(ErrorMailDTO.class);
    verify(this.mailService, times(1))
        .sendErrorEmailNotification(errorMailDTOArgumentCaptor.capture());

    var templateData = errorMailDTOArgumentCaptor.getValue().getTemplateData();
    TemplateDataDTO textData =
        templateData.stream().filter(t -> "text".equals(t.getKey())).findFirst().orElse(null);
    assertThat(textData).isNotNull();
    assertThat(textData.getValue()).contains("Last Message Date");
    assertThat(textData.getValue()).contains(expectedLastMessageDate);
  }
}
