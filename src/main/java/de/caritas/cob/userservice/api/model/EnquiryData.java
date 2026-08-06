package de.caritas.cob.userservice.api.model;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@AllArgsConstructor
@RequiredArgsConstructor
@Getter
@Setter
@EqualsAndHashCode
@ToString
public class EnquiryData {

  private final User user;
  private final Long sessionId;
  private final String message;
  private final String language;
  private String type;
  private String consultantEmail;
  private String matrixEventId;
}
