package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.MatrixCallGuest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatrixCallGuestRepository extends JpaRepository<MatrixCallGuest, Long> {
  Optional<MatrixCallGuest> findByBindingIdAndMatrixUserId(Long bindingId, String matrixUserId);

  List<MatrixCallGuest> findByBindingIdAndRevokedAtIsNull(Long bindingId);

  /**
   * FE#2209 round 4: the seats not yet taken back, as a locking read. Under the binding's row lock
   * it sees every seat committed before, also one a join committed while the caller waited on that
   * lock, which a plain read of the transaction's REPEATABLE READ snapshot would miss.
   */
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query(
      "select g from MatrixCallGuest g where g.bindingId = :bindingId and g.revokedAt is null"
          + " order by g.id")
  List<MatrixCallGuest> lockOutstandingByBindingId(
      @org.springframework.data.repository.query.Param("bindingId") Long bindingId);

  List<MatrixCallGuest> findByBindingId(Long bindingId);

  boolean existsByBindingIdAndMatrixUserIdAndRevokedAtIsNull(Long bindingId, String matrixUserId);

  /**
   * Ended calls whose guest seats were not all taken back yet (reconcile sweep, US#1477), skipping
   * seats tried after {@code retryBefore}. Never tried first, then least recently tried, so rooms
   * that keep failing cannot starve later ones.
   */
  @org.springframework.data.jpa.repository.Query(
      "select b.mediaRoomId from MatrixCallBinding b, MatrixCallGuest g"
          + " where g.bindingId = b.id and b.endedAt is not null and g.revokedAt is null"
          + " and (g.lastRevokeAttemptAt is null or g.lastRevokeAttemptAt <= :retryBefore)"
          + " group by b.mediaRoomId"
          + " order by coalesce(max(g.lastRevokeAttemptAt), 0), b.mediaRoomId")
  List<String> findEndedMediaRoomsWithOutstandingGuests(
      @org.springframework.data.repository.query.Param("retryBefore") long retryBefore,
      org.springframework.data.domain.Pageable page);

  /**
   * Security review 5901360577, round 8 (F2): running 1:1 session calls that still hold an
   * unrevoked seat (not tried after {@code retryBefore}) and whose rule may have lapsed at {@code
   * now}. A call whose caller is the session's assigned counsellor (same Träger, not deleted, not
   * inactive) and that has an active, not deleted counsellor of the Träger with a live membership
   * in it is left out: the lifecycle would keep its seats. This is the exact complement of the rule
   * in MatrixCallGuestService.revokeOnceTheSessionCallRuleLapsed, so every call that rule would
   * take seats from stays a candidate. A missing account_inactivity row counts as active, as in
   * AccountInactivityService. Round 10: as in the lifecycle, an unobserved call room has no
   * counsellor in it, and a Matrix id two not deleted accounts of the Träger share is nobody
   * (SoleCounsellor). Least recently swept first (never swept first), so no candidate starves
   * behind others; the sweep stamps last_swept_at.
   */
  @org.springframework.data.jpa.repository.Query(
      nativeQuery = true,
      value =
          "select b.media_room_id from matrix_call_binding b"
              + " where b.ended_at is null and b.session_id is not null"
              + " and exists (select 1 from matrix_call_guest g where g.binding_id = b.id"
              + "   and g.revoked_at is null"
              + "   and (g.last_revoke_attempt_at is null"
              + "     or g.last_revoke_attempt_at <= :retryBefore))"
              + " and not ("
              + "   exists (select 1 from session s, consultant c"
              + "     where s.id = b.session_id and s.tenant_id = b.tenant_id"
              + "     and c.consultant_id = s.consultant_id and c.delete_date is null"
              + "     and c.tenant_id = b.tenant_id and c.matrix_user_id = b.caller_matrix_id"
              + "     and not exists (select 1 from account_inactivity a"
              + "       where a.identity_id = c.consultant_id and a.status <> 'ACTIVE'))"
              + "   and b.media_observed_at is not null"
              + "   and exists (select 1 from matrix_call_device d, consultant p"
              + "     where d.binding_id = b.id and d.expires_at > :now"
              + "     and p.matrix_user_id = d.sender_matrix_id and p.delete_date is null"
              + "     and p.tenant_id = b.tenant_id"
              + "     and not exists (select 1 from consultant q"
              + "       where q.matrix_user_id = p.matrix_user_id and q.delete_date is null"
              + "       and q.tenant_id = b.tenant_id and q.consultant_id <> p.consultant_id)"
              + "     and not exists (select 1 from account_inactivity a2"
              + "       where a2.identity_id = p.consultant_id and a2.status <> 'ACTIVE')))"
              + " order by coalesce(b.last_swept_at, 0), b.id")
  List<String> findRunningSessionMediaRoomsWhoseRuleMayHaveLapsed(
      @org.springframework.data.repository.query.Param("retryBefore") long retryBefore,
      @org.springframework.data.repository.query.Param("now") long now,
      org.springframework.data.domain.Pageable page);
}
