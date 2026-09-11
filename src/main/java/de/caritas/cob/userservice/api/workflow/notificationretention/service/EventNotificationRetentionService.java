package de.caritas.cob.userservice.api.workflow.notificationretention.service;

import de.caritas.cob.userservice.api.model.EventNotification;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ages {@link EventNotification} rows out of the database (KDG epic #1010, task 2a).
 *
 * <p>The table had no retention at all. Every row carries the recipient's Keycloak UUID, a session
 * reference and third-party names, and {@code read_date} is a second-precision record of when the
 * recipient looked at a given notification — an activity profile that grew for as long as the
 * account existed. None of that has a purpose once the notification has been seen.
 *
 * <p>Two cutoffs rather than one: a read notification has already done its job, so it goes after
 * the shorter period, while the absolute cutoff makes sure an unread feed cannot grow without bound
 * either.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventNotificationRetentionService {

  private final @NonNull EventNotificationRepository eventNotificationRepository;

  @Value("${event.notification.retention.read.days:90}")
  private int readRetentionDays;

  @Value("${event.notification.retention.absolute.days:365}")
  private int absoluteRetentionDays;

  /**
   * Purges notifications that have outlived their retention period.
   *
   * <p>A non-positive setting disables that cutoff rather than deleting everything, so a
   * misconfigured or empty value can never wipe the table.
   */
  @Transactional
  public void purgeExpiredNotifications() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

    int purgedRead = 0;
    if (readRetentionDays > 0) {
      purgedRead = eventNotificationRepository.deleteReadBefore(now.minusDays(readRetentionDays));
    }

    int purgedAbsolute = 0;
    if (absoluteRetentionDays > 0) {
      purgedAbsolute =
          eventNotificationRepository.deleteCreatedBefore(now.minusDays(absoluteRetentionDays));
    }

    if (purgedRead > 0 || purgedAbsolute > 0) {
      log.info(
          "Event notification retention purged {} read notifications older than {} days and {}"
              + " notifications older than {} days",
          purgedRead,
          readRetentionDays,
          purgedAbsolute,
          absoluteRetentionDays);
    }
  }
}
