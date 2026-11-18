package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * How advice seekers book a counsellor (ORISO-Frontend#2287), one row per counsellor; without a row
 * the defaults apply.
 */
@Entity
@Table(name = "calendar_booking_setting")
@Getter
@Setter
@NoArgsConstructor
public class CalendarBookingSetting {

  public static final boolean DEFAULT_AUTO_ACCEPT = true;
  public static final int DEFAULT_NOTICE_DAYS = 1;
  public static final int DEFAULT_SLOT_MINUTES = 60;

  @Id
  @Column(name = "consultant_id", length = 36, nullable = false)
  private String consultantId;

  /** A booking in a Sprechstunde outside the notice period is confirmed at once. */
  @Column(name = "auto_accept", nullable = false)
  private boolean autoAccept = DEFAULT_AUTO_ACCEPT;

  /** Days before its start from which a booking needs the counsellor's confirmation. */
  @Column(name = "notice_days", nullable = false)
  private int noticeDays = DEFAULT_NOTICE_DAYS;

  /** 30 or 60. */
  @Column(name = "slot_minutes", nullable = false)
  private int slotMinutes = DEFAULT_SLOT_MINUTES;

  /** The settings of a counsellor without a row. */
  public static CalendarBookingSetting defaults(String consultantId) {
    var setting = new CalendarBookingSetting();
    setting.setConsultantId(consultantId);
    return setting;
  }
}
