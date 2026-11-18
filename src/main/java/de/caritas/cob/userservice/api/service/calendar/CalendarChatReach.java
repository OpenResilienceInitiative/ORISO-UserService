package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.facade.InternalSubsetChatFacade;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Which colleagues a counsellor may have a private internal chat with (ORISO-Frontend#2305). Where
 * the Träger allows none, an appointment with that colleague can only be on site (Frank,
 * 2026-11-17: "dann zeigst du halt einfach nur einen Vor-Ort-Termin an").
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CalendarChatReach {

  private final ConsultantRepository consultants;
  private final InternalSubsetChatFacade rooms;

  /** The ids among {@code colleagues} the caller may chat with; empty when that is unknown. */
  Optional<Set<String>> of(String callerId, Collection<Consultant> colleagues) {
    try {
      return consultants
          .findByIdAndDeleteDateIsNull(callerId)
          .map(caller -> rooms.reachableColleagues(caller, colleagues));
    } catch (RuntimeException failure) {
      log.warn(
          "Internal chat reach for the calendar could not be read: {}",
          failure.getClass().getSimpleName());
      return Optional.empty();
    }
  }
}
