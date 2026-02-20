package de.caritas.cob.userservice.api.workflow.delete.service;

import static de.caritas.cob.userservice.api.service.emailsupplier.EmailSupplier.TEMPLATE_FREE_TEXT;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;

import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.service.helper.MailService;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowInfo;
import de.caritas.cob.userservice.mailservice.generated.web.model.ErrorMailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Service class to build an error mail for all WorkflowErrorLogService workflow errors. */
@Service
@RequiredArgsConstructor
public class WorkflowErrorMailService {

  private final @NonNull MailService mailService;

  private final @NonNull TenantTemplateSupplier tenantTemplateSupplier;

  @Value("${app.base.url}")
  private String applicationBaseUrl;

  @Value("${multitenancy.enabled}")
  private Boolean multitenancyEnabled;

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
        new TemplateDataDTO()
            .key("text")
            .value(buildCombinedHtmlText(workflowErrors, workflowInfos)));

    if (!multitenancyEnabled) {
      templateAttributes.add(new TemplateDataDTO().key("url").value(applicationBaseUrl));
    } else {
      templateAttributes.addAll(tenantTemplateSupplier.getTemplateAttributes());
    }

    ErrorMailDTO errorMailDTO =
        new ErrorMailDTO().template(TEMPLATE_FREE_TEXT).templateData(templateAttributes);
    this.mailService.sendErrorEmailNotification(errorMailDTO);
  }

  private String buildCombinedHtmlText(
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
      stringBuilder.append("<h2>No errors occured</h2>");
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
            .append("Successfully deleted users:</h2>")
            .append("<ul>");

    deletionInfo.forEach(
        info ->
            stringBuilder
                .append("<li>")
                .append("User ID: ")
                .append(info.getUserId())
                .append("</li><li>User Name: ")
                .append(info.getUserName())
                .append("</li><li>Last Message Date: ")
                .append(info.getLastMessageDate())
                .append("</li><hr>"));

    stringBuilder.append("</ul>");
    return stringBuilder.toString();
  }

  private String convertErrorsToHtmlText(List<DeletionWorkflowError> workflowErrors) {
    StringBuilder stringBuilder =
        new StringBuilder()
            .append("<h2>")
            .append("(")
            .append(workflowErrors.size())
            .append(") ")
            .append("Errors during deletion workflow:</h2>");

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
