package de.caritas.cob.userservice.api.workflow.delete.service;

import static de.caritas.cob.userservice.api.service.emailsupplier.EmailSupplier.TEMPLATE_FREE_TEXT;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;

import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.service.helper.MailService;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowInfo;
import de.caritas.cob.userservice.mailservice.generated.web.model.ErrorMailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Service class to build an error mail for all WorkflowErrorLogService workflow errors. */
@Service
@RequiredArgsConstructor
public class WorkflowResultsMailService {

  private final @NonNull MailService mailService;

  private final @NonNull TenantTemplateSupplier tenantTemplateSupplier;

  private final UsernameTranscoder usernameTranscoder;

  @Value("${app.base.url}")
  private String applicationBaseUrl;

  @Value("${multitenancy.enabled}")
  private Boolean multitenancyEnabled;

  private static final DateTimeFormatter LAST_MESSAGE_DATE_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

  /**
   * Builds an {@link ErrorMailDTO} containing a text with all workflow errors and sends it to the
   * {@link MailService}.
   *
   * @param workflowErrors the {@link DeletionWorkflowError} objects
   */
  public void buildAndSendErrorMail(List<DeletionWorkflowError> workflowErrors) {
    buildAndSendMail(workflowErrors, null);
  }

  /**
   * Builds an {@link ErrorMailDTO} containing text with both workflow errors and deletion info,
   * then sends it to the {@link MailService}.
   *
   * @param workflowErrors the {@link DeletionWorkflowError} objects
   * @param workflowInfos the {@link DeletionWorkflowInfo} objects for successfully deleted
   *     users/sessions
   */
  public void buildAndSendMail(
      List<DeletionWorkflowError> workflowErrors, List<DeletionWorkflowInfo> workflowInfos) {
    var templateAttributes = new ArrayList<TemplateDataDTO>();
    templateAttributes.add(new TemplateDataDTO().key("subject").value("Deletion workflow report"));
    templateAttributes.add(
        new TemplateDataDTO().key("text").value(buildHtmlText(workflowErrors, workflowInfos)));

    if (!multitenancyEnabled) {
      templateAttributes.add(new TemplateDataDTO().key("url").value(applicationBaseUrl));
    } else {
      templateAttributes.addAll(tenantTemplateSupplier.getTemplateAttributes());
    }

    ErrorMailDTO errorMailDTO =
        new ErrorMailDTO().template(TEMPLATE_FREE_TEXT).templateData(templateAttributes);
    this.mailService.sendErrorEmailNotification(errorMailDTO);
  }

  private String buildHtmlText(
      List<DeletionWorkflowError> deletionErrors, List<DeletionWorkflowInfo> deletionInfo) {
    StringBuilder stringBuilder = new StringBuilder();

    if (isNotEmpty(deletionInfo)) {
      stringBuilder.append(convertInfoToHtmlText(deletionInfo));
    } else {
      stringBuilder.append("<h2>No deletion info</h2>");
    }

    if (isNotEmpty(deletionErrors)) {
      stringBuilder.append(convertErrorsToHtmlText(deletionErrors));
    } else {
      stringBuilder.append("<h2>No errors occurred</h2>");
    }

    return stringBuilder.toString();
  }

  private String convertInfoToHtmlText(List<DeletionWorkflowInfo> deletionInfo) {
    StringBuilder stringBuilder =
        new StringBuilder()
            .append("<h2>")
            .append("(")
            .append(deletionInfo.size())
            .append(") ")
            .append("Perform deletion for users:</h2>")
            .append("<ul>");

    deletionInfo.forEach(
        info ->
            stringBuilder
                .append("<li>")
                .append("User ID: ")
                .append(info.getUserId())
                .append("User rocketchat ID: ")
                .append(info.getRcUserId())
                .append("</li><li>User Name: ")
                .append(usernameTranscoder.decodeUsername(info.getUserName()))
                .append("</li><li>Last Message Date: ")
                .append(formatLastMessageDate(info.getLastMessageDate()))
                .append("</li>")
                .append("<hr>"));

    stringBuilder.append("</ul>");
    return stringBuilder.toString();
  }

  private String formatLastMessageDate(Date date) {
    if (date == null) {
      return "";
    }
    return LAST_MESSAGE_DATE_FORMATTER.format(
        LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC));
  }

  private String convertErrorsToHtmlText(List<DeletionWorkflowError> workflowErrors) {
    StringBuilder stringBuilder =
        new StringBuilder()
            .append("<h2>")
            .append("(")
            .append(workflowErrors.size())
            .append(") ")
            .append("Errors during deletion workflow:</h2>")
            .append("<ul>");

    workflowErrors.forEach(
        workflowError ->
            stringBuilder
                .append("<li>")
                .append("SourceType = ")
                .append(workflowError.getDeletionSourceType())
                .append("</li><li>TargetType = ")
                .append(workflowError.getDeletionTargetType())
                .append("</li><li>Identifier = ")
                .append(workflowError.getIdentifier())
                .append("</li><li>Reason = ")
                .append(workflowError.getReason())
                .append("</li><li>Timestamp = ")
                .append(workflowError.getTimestamp())
                .append("</li><hr>"));

    return stringBuilder.toString();
  }
}
