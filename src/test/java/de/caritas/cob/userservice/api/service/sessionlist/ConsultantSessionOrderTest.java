package de.caritas.cob.userservice.api.service.sessionlist;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionDTO;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConsultantSessionOrderTest {

  private static ConsultantSessionResponseDTO conversation(
      long id, LocalDate enquiryMessage, String createdAt) {
    return new ConsultantSessionResponseDTO()
        .session(new SessionDTO().id(id).createDate(createdAt))
        .latestMessage(enquiryMessage == null ? null : Date.valueOf(enquiryMessage));
  }

  private static List<Long> idsInOrder(ConsultantSessionResponseDTO... conversations) {
    var list = new ArrayList<>(List.of(conversations));
    list.sort(ConsultantSessionOrder.LATEST_ACTIVITY_FIRST);
    return list.stream().map(c -> c.getSession().getId()).toList();
  }

  @Test
  void aDirectInvitationGuestWhoJustArrivedIsOnTop_NotBehindOlderConversations() {
    // ORISO-Frontend#2542: no enquiry message, created a moment ago.
    var guest = conversation(402L, null, "2026-11-25T14:52:04Z");
    var lastWeek = conversation(262L, LocalDate.of(2026, 11, 18), "2026-11-18T09:00:00Z");
    var lastMonth = conversation(8L, LocalDate.of(2026, 10, 20), "2026-10-20T09:00:00Z");

    assertThat(idsInOrder(lastMonth, lastWeek, guest)).containsExactly(402L, 262L, 8L);
  }

  @Test
  void conversationsWithEnquiryMessageKeepTheirOrder_LatestFirst() {
    var older = conversation(1L, LocalDate.of(2026, 11, 1), "2026-11-01T08:00:00Z");
    var newer = conversation(2L, LocalDate.of(2026, 11, 20), "2026-10-01T08:00:00Z");

    assertThat(idsInOrder(older, newer)).containsExactly(2L, 1L);
  }

  @Test
  void conversationsWithoutEnquiryMessageAreOrderedByCreation_LatestFirst() {
    var first = conversation(400L, null, "2026-11-25T14:48:54Z");
    var second = conversation(401L, null, "2026-11-25T14:50:10Z");

    assertThat(idsInOrder(first, second)).containsExactly(401L, 400L);
  }

  @Test
  void aConversationWithoutAnyDateStaysLast_AndAnUnreadableDateDoesNotBreakTheList() {
    var dated = conversation(1L, LocalDate.of(2020, 1, 1), null);
    var undated = conversation(2L, null, null);
    var unreadable = conversation(3L, null, "not a date");
    var noSession = new ConsultantSessionResponseDTO();

    var list = new ArrayList<>(List.of(undated, unreadable, noSession, dated));
    list.sort(ConsultantSessionOrder.LATEST_ACTIVITY_FIRST);

    assertThat(list.get(0)).isSameAs(dated);
  }
}
