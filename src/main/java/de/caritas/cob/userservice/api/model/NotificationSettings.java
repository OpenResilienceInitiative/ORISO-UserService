package de.caritas.cob.userservice.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class NotificationSettings {

  boolean initialEnquiryNotificationEnabled;
  boolean newChatMessageNotificationEnabled;
  boolean reassignmentNotificationEnabled;
  boolean appointmentNotificationEnabled;

  // The notification matrix, ADR-019 in ORISO-Frontend.
  boolean assignmentNotificationEnabled;
  boolean feedbackNotificationEnabled;
  boolean serviceNoticeNotificationEnabled;
}
