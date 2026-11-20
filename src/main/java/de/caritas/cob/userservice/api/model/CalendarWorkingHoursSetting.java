package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The settings of a counsellor's working hours that are no window (ADR-032, contract addendum
 * v1.2), one row per counsellor: the Bundesland whose public holidays count as days off. The server
 * computes no holidays; the calendar does that from the region.
 */
@Entity
@Table(name = "calendar_working_hours_setting")
@Getter
@Setter
@NoArgsConstructor
public class CalendarWorkingHoursSetting {

  @Id
  @Column(name = "consultant_id", length = 36, nullable = false)
  private String consultantId;

  /** ISO 3166-2:DE suffix (e.g. BY); null when holidays are not days off. */
  @Column(name = "holiday_region", length = 2)
  private String holidayRegion;
}
