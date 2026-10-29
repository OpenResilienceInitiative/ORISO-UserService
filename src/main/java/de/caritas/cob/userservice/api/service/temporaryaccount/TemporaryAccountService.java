package de.caritas.cob.userservice.api.service.temporaryaccount;

import de.caritas.cob.userservice.api.adapters.web.dto.TemporaryAccountDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.TemporaryAccountSnapshot;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** The guest's own view of a direct-link temporary account and their retention answer (§5). */
@Service
@RequiredArgsConstructor
public class TemporaryAccountService {

  private final @NonNull UserRepository userRepository;

  public TemporaryAccountDTO own(User user) {
    return toDto(requireSnapshot(user));
  }

  /** Stores the guest's once-per-conversation answer under GUEST_DECIDES. */
  public TemporaryAccountDTO decide(User user, boolean keepMessages) {
    var snapshot = requireSnapshot(user);
    if (snapshot.getMessageRetention() != MessageRetentionMode.GUEST_DECIDES) {
      throw new ConflictException("The platform does not ask temporary accounts");
    }
    if (snapshot.getKeepMessages() != null || snapshot.getDeletedAt() != null) {
      throw new ConflictException("The guest already answered");
    }
    snapshot.setKeepMessages(keepMessages);
    userRepository.save(user);
    return toDto(snapshot);
  }

  /** Null for every account that is not a direct-link temporary account. */
  public static TemporaryAccountDTO toDtoOrNull(User user) {
    return user == null
            || !user.isTemporaryAccount()
            || user.getTemporaryAccountSnapshot() == null
            || user.getTemporaryAccountSnapshot().getMedium() == null
        ? null
        : toDto(user.getTemporaryAccountSnapshot());
  }

  public static TemporaryAccountDTO toDto(TemporaryAccountSnapshot snapshot) {
    var dto = new TemporaryAccountDTO();
    dto.setMedium(
        java.util.Arrays.stream(TemporaryAccountDTO.MediumEnum.values())
            .filter(
                medium ->
                    snapshot.getMedium() != null
                        && medium.getValue().equals(snapshot.getMedium().name()))
            .findFirst()
            .orElse(null));
    dto.setMessageRetention(
        snapshot.getMessageRetention() == null
            ? null
            : de.caritas.cob.userservice.api.adapters.web.dto.MessageRetentionMode.fromValue(
                snapshot.getMessageRetention().name()));
    dto.setKeepMessages(snapshot.getKeepMessages());
    dto.setKeepsMessages(snapshot.keepsMessages());
    dto.setDeleted(snapshot.getDeletedAt() != null);
    return dto;
  }

  private static TemporaryAccountSnapshot requireSnapshot(User user) {
    if (user == null || user.getTemporaryAccountSnapshot() == null) {
      throw new NotFoundException("No temporary account");
    }
    return user.getTemporaryAccountSnapshot();
  }
}
