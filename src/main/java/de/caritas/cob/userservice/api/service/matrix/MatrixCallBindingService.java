package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MatrixCallBindingService {
  private final MatrixCallBindingRepository bindings;
  private final MatrixCallBindingWriter writer;

  public java.util.List<String> unobservedMediaRooms(long now) {
    return bindings.findObservationCandidates(
        now, org.springframework.data.domain.PageRequest.of(0, 100));
  }

  public java.util.List<String> expiredMediaRooms(long now) {
    return bindings.findExpiryCandidates(
        now, org.springframework.data.domain.PageRequest.of(0, 100));
  }

  public boolean register(MatrixCallBinding proposed) {
    var existing = existing(proposed);
    if (existing.isPresent()) return matches(existing.get(), proposed);
    try {
      writer.insert(proposed);
      return true;
    } catch (DataIntegrityViolationException collision) {
      // Read the committed winner; unrelated database errors must still propagate.
      return existing(proposed).map(value -> matches(value, proposed)).orElseThrow(() -> collision);
    }
  }

  public java.util.Set<String> invitedMembers(String sourceRoom, String callId) {
    return bindings
        .findBySourceRoomIdAndCallId(sourceRoom, callId)
        .map(value -> java.util.Set.copyOf(value.getInvitedMatrixIds()))
        .orElseGet(java.util.Set::of);
  }

  private Optional<MatrixCallBinding> existing(MatrixCallBinding proposed) {
    return bindings
        .findBySourceRoomIdAndCallId(proposed.getSourceRoomId(), proposed.getCallId())
        .or(() -> bindings.findByMediaRoomId(proposed.getMediaRoomId()));
  }

  private boolean matches(MatrixCallBinding saved, MatrixCallBinding proposed) {
    return Objects.equals(saved.getSourceRoomId(), proposed.getSourceRoomId())
        && Objects.equals(saved.getCallId(), proposed.getCallId())
        && Objects.equals(saved.getMediaRoomId(), proposed.getMediaRoomId())
        && Objects.equals(saved.getCallerMatrixId(), proposed.getCallerMatrixId())
        && Objects.equals(saved.getSessionId(), proposed.getSessionId())
        && Objects.equals(saved.getChatId(), proposed.getChatId())
        && saved.getInvitedAt() == proposed.getInvitedAt()
        && saved.getInviteExpiresAt() == proposed.getInviteExpiresAt()
        && saved.isVideo() == proposed.isVideo()
        && Objects.equals(saved.getTenantId(), proposed.getTenantId());
  }
}
