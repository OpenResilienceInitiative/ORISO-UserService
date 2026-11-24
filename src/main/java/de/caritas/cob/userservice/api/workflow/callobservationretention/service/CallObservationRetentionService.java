package de.caritas.cob.userservice.api.workflow.callobservationretention.service;

import de.caritas.cob.userservice.api.port.out.MatrixCallObservationEndRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallObservationRepository;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ages call observations (#1624) out of the database. A row holds the creator's Matrix id and the
 * room and call ids; it is only needed to show a call's state and to write the timeline entries,
 * which happens within minutes of the call. Rows are removed once their lease and end are older
 * than the retention period, so the creator's identity is not kept beyond that, also after the
 * account or chat is deleted. The stored ends of a call (#1628) hold the ending person's Matrix id
 * and are aged out the same way.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallObservationRetentionService {

  private final @NonNull MatrixCallObservationRepository observations;
  private final @NonNull MatrixCallObservationEndRepository ends;

  @Value("${matrix.call.observation.retention.days:30}")
  private int retentionDays;

  /** A non-positive setting disables the purge instead of deleting everything. */
  @Transactional
  public int purgeExpired() {
    if (retentionDays <= 0) {
      return 0;
    }
    long cutoff = System.currentTimeMillis() - Duration.ofDays(retentionDays).toMillis();
    int purged = observations.deleteFinishedBefore(cutoff) + ends.deleteEndedBefore(cutoff);
    if (purged > 0) {
      log.info(
          "Call observation retention purged {} rows older than {} days", purged, retentionDays);
    }
    return purged;
  }
}
