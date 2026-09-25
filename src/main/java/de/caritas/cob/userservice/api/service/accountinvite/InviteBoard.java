package de.caritas.cob.userservice.api.service.accountinvite;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.service.accountinvite.InviteProgress.Phase;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Admin's invite list with each invite's progress, and the phase counts behind its status
 * tiles. The counts cover every invite in scope, not one page, so the list is paged here.
 */
@Component
@RequiredArgsConstructor
public class InviteBoard {

  private static final int DELIVERY_BATCH = 500;
  private static final int MAX_PAGE_SIZE = 100;
  private static final int DEFAULT_PAGE_SIZE = 20;

  private final @NonNull AccountInviteService accountInviteService;
  private final @NonNull InviteEmailDeliveryRepository deliveryRepository;
  private final @NonNull UnitQueue unitQueue;

  /** One invite with what is derived on read. */
  public record Row(
      AccountInvite invite,
      InviteEmailDelivery latestDelivery,
      InviteQueueProblem queueProblem,
      InviteProgress progress) {}

  /** {@code phaseCounts} ignore the status and phase filters, so the tiles stay put. */
  public record Listing(Page<Row> rows, Map<Phase, Long> phaseCounts) {}

  @Transactional(readOnly = true)
  public Listing list(
      AccountInviteTargetRole targetRole,
      AccountInviteStatus status,
      Phase phase,
      Long tenantId,
      String query,
      int page,
      int size) {
    PageRequest pageRequest = PageRequest.of(Math.max(page, 0), clampSize(size));
    List<Row> rows = rowsOf(accountInviteService.listAllInvites(targetRole, tenantId, query));
    Map<Phase, Long> counts = new EnumMap<>(Phase.class);
    for (Phase each : Phase.values()) {
      counts.put(each, 0L);
    }
    rows.forEach(row -> counts.merge(row.progress().phase(), 1L, Long::sum));
    List<Row> matching =
        rows.stream()
            .filter(row -> status == null || row.invite().getStatus() == status)
            .filter(row -> phase == null || row.progress().phase() == phase)
            .toList();
    int from = (int) Math.min(pageRequest.getOffset(), matching.size());
    int to = Math.min(from + pageRequest.getPageSize(), matching.size());
    return new Listing(
        new PageImpl<>(matching.subList(from, to), pageRequest, matching.size()),
        Collections.unmodifiableMap(counts));
  }

  /** The same derivation for a single invite, e.g. in the answer to a change. */
  @Transactional(readOnly = true)
  public Row rowOf(AccountInvite invite) {
    if (invite.getId() == null) {
      return row(invite, null, LocalDateTime.now());
    }
    return row(
        invite,
        deliveryRepository
            .findFirstByAccountInviteIdOrderByCreateDateDesc(invite.getId())
            .orElse(null),
        LocalDateTime.now());
  }

  private List<Row> rowsOf(List<AccountInvite> invites) {
    Map<Long, InviteEmailDelivery> latest = new HashMap<>();
    List<Long> ids = invites.stream().map(AccountInvite::getId).filter(Objects::nonNull).toList();
    for (List<Long> batch : Lists.partition(ids, DELIVERY_BATCH)) {
      // Newest first, so the first delivery seen per invite is its latest.
      deliveryRepository
          .findByAccountInviteIdInOrderByCreateDateDesc(batch)
          .forEach(delivery -> latest.putIfAbsent(delivery.getAccountInviteId(), delivery));
    }
    LocalDateTime now = LocalDateTime.now();
    return invites.stream().map(invite -> row(invite, latest.get(invite.getId()), now)).toList();
  }

  private Row row(AccountInvite invite, InviteEmailDelivery latestDelivery, LocalDateTime now) {
    InviteQueueProblem problem = unitQueue.problemOf(invite);
    return new Row(
        invite, latestDelivery, problem, InviteProgress.of(invite, latestDelivery, problem, now));
  }

  private static int clampSize(int size) {
    if (size < 1) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.min(size, MAX_PAGE_SIZE);
  }
}
