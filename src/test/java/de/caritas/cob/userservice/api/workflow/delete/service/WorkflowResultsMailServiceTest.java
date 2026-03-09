package de.caritas.cob.userservice.api.workflow.delete.service;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.ROCKET_CHAT;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.service.helper.MailService;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowInfo;
import de.caritas.cob.userservice.mailservice.generated.web.model.ErrorMailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
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

  @Mock private UsernameTranscoder usernameTranscoder;

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
  public void buildASendErrorMail_Should_sendMail_When_workflowErrorsAreEmpty() {
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
                .rcUserId("rc-user-123")
                .userName("encodedTestUser")
                .lastMessageDate(lastMessageDate)
                .build());
    doReturn("testUser").when(usernameTranscoder).decodeUsername("encodedTestUser");

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
    assertThat(textData.getValue()).contains("Perform deletion for users:");
    assertThat(textData.getValue()).contains("user123");
    assertThat(textData.getValue()).contains("User rocketchat ID:");
    assertThat(textData.getValue()).contains("rc-user-123");
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
                .rcUserId("rc-user-123")
                .userName("encodedTestUser")
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
    doReturn("testUser").when(usernameTranscoder).decodeUsername("encodedTestUser");

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
    // Verify an info section comes first
    assertThat(textData.getValue()).contains("Perform deletion for users:");
    assertThat(textData.getValue()).contains("user123");
    assertThat(textData.getValue()).contains("User rocketchat ID:");
    assertThat(textData.getValue()).contains("rc-user-123");
    assertThat(textData.getValue()).contains("testUser");
    // Verify errors section
    assertThat(textData.getValue()).contains("Errors during deletion workflow");
    assertThat(textData.getValue()).contains("error reason");
    assertThat(textData.getValue()).contains("errorId");
  }

  @Test
  public void buildAndSendMail_Should_renderMarkup_with_headers_When_bothCollectionsAreEmpty() {
    // when
    this.workflowResultsMailService.buildAndSendMail(emptyList(), emptyList());

    // then
    String htmlText = captureTextTemplateValue();
    assertThat(htmlText).isEqualTo("<h2>No deletion info</h2><h2>No errors occurred</h2>");
  }

  @Test
  public void buildAndSendMail_Should_renderMarkup_When_onlyInfoCollectionHasData() {
    // given
    Date lastMessageDate = new Date(1700000000000L);
    List<DeletionWorkflowInfo> deletionInfo =
        Collections.singletonList(
            DeletionWorkflowInfo.builder()
                .userId("user123")
                .rcUserId("rc-user-123")
                .userName("encodedTestUser")
                .lastMessageDate(lastMessageDate)
                .build());
    doReturn("testUser").when(usernameTranscoder).decodeUsername("encodedTestUser");

    // when
    this.workflowResultsMailService.buildAndSendMail(emptyList(), deletionInfo);

    // then
    String htmlText = captureTextTemplateValue();
    assertThat(htmlText).contains("<h2>(1) Perform deletion for users:</h2>");
    assertThat(htmlText).contains("<ul>");
    assertThat(htmlText).contains("<li>User ID: user123</li>");
    assertThat(htmlText).contains("<li>User rocketchat ID: rc-user-123</li>");
    assertThat(htmlText).contains("<li>User Name: testUser</li>");
    assertThat(htmlText).contains("<li>Last Message Date: 2023-11-14T22:13:20.000Z");
    assertThat(htmlText).contains("<hr></li>");
    assertThat(htmlText).contains("</ul>");
    assertThat(htmlText).contains("<h2>No errors occurred</h2>");
  }

  @Test
  public void buildAndSendMail_Should_renderMarkup_When_onlyErrorCollectionHasData() {
    // given
    List<DeletionWorkflowError> workflowErrors =
        Collections.singletonList(
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
    String htmlText = captureTextTemplateValue();
    assertThat(htmlText).contains("<h2>No deletion info</h2>");
    assertThat(htmlText).contains("<h2>(1) Errors during deletion workflow:</h2>");
    assertThat(htmlText).contains("<ul>");
    assertThat(htmlText).contains("<li>SourceType = ASKER</li>");
    assertThat(htmlText).contains("<li>TargetType = ROCKET_CHAT</li>");
    assertThat(htmlText).contains("<li>Identifier = id123</li>");
    assertThat(htmlText).contains("<li>Reason = test reason</li>");
    assertThat(htmlText).contains("<li>Timestamp = ");
    assertThat(htmlText).contains("<hr></li>");
    assertThat(htmlText).contains("</ul>");
  }

  @Test
  public void buildAndSendMail_Should_renderMarkup_When_bothCollectionsHaveData() {
    // given
    Date lastMessageDate = new Date(1700000000000L);
    List<DeletionWorkflowInfo> deletionInfo =
        Collections.singletonList(
            DeletionWorkflowInfo.builder()
                .userId("user123")
                .rcUserId("rc-user-123")
                .userName("encodedTestUser")
                .lastMessageDate(lastMessageDate)
                .build());
    List<DeletionWorkflowError> workflowErrors =
        Collections.singletonList(
            DeletionWorkflowError.builder()
                .deletionSourceType(ASKER)
                .deletionTargetType(ROCKET_CHAT)
                .timestamp(nowInUtc())
                .reason("error reason")
                .identifier("errorId")
                .build());
    doReturn("testUser").when(usernameTranscoder).decodeUsername("encodedTestUser");

    // when
    this.workflowResultsMailService.buildAndSendMail(workflowErrors, deletionInfo);

    // then
    String htmlText = captureTextTemplateValue();
    assertThat(htmlText).contains("<h2>(1) Perform deletion for users:</h2>");
    assertThat(htmlText).contains("<h2>(1) Errors during deletion workflow:</h2>");
    assertThat(htmlText.indexOf("<h2>(1) Perform deletion for users:</h2>"))
        .isLessThan(htmlText.indexOf("<h2>(1) Errors during deletion workflow:</h2>"));
    assertThat(htmlText).contains("<li>User ID: user123</li>");
    assertThat(htmlText).contains("<li>Reason = error reason</li>");
    assertThat(htmlText).contains("<hr></li>");
  }

  @Test
  public void buildAndSendMail_Should_callUsernameTranscoder_ForEachDeletionInfoEntry() {
    // given
    Date lastMessageDate = new Date(1700000000000L);
    List<DeletionWorkflowInfo> deletionInfo =
        asList(
            DeletionWorkflowInfo.builder()
                .userId("user123")
                .rcUserId("rc-user-123")
                .userName("encodedUser1")
                .lastMessageDate(lastMessageDate)
                .build(),
            DeletionWorkflowInfo.builder()
                .userId("user456")
                .rcUserId("rc-user-456")
                .userName("encodedUser2")
                .lastMessageDate(lastMessageDate)
                .build());

    doReturn("decodedUser1").when(usernameTranscoder).decodeUsername("encodedUser1");
    doReturn("decodedUser2").when(usernameTranscoder).decodeUsername("encodedUser2");

    // when
    this.workflowResultsMailService.buildAndSendMail(emptyList(), deletionInfo);

    // then
    verify(usernameTranscoder).decodeUsername("encodedUser1");
    verify(usernameTranscoder).decodeUsername("encodedUser2");
  }

  private String captureTextTemplateValue() {
    ArgumentCaptor<ErrorMailDTO> errorMailDTOArgumentCaptor =
        ArgumentCaptor.forClass(ErrorMailDTO.class);
    verify(this.mailService, times(1))
        .sendErrorEmailNotification(errorMailDTOArgumentCaptor.capture());

    var templateData = errorMailDTOArgumentCaptor.getValue().getTemplateData();
    TemplateDataDTO textData =
        templateData.stream().filter(t -> "text".equals(t.getKey())).findFirst().orElse(null);
    assertThat(textData).isNotNull();
    return textData.getValue();
  }
}
