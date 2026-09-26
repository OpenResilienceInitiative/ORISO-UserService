package de.caritas.cob.userservice.api.workflow.enquirynotification.model;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EnquiriesNotificationMailContent {

  private Long amountOfOpenEnquiries;
  private Long agencyId;
  private String agencyName;
  private Long tenantId;
  private String baseUrl;
  private LocalDateTime oldestEnquiryDate;
  private LocalDateTime generatedAt;
}
