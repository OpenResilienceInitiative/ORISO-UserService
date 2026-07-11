package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.port.out.ChatOccurrenceExceptionRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatOccurrenceQueryService {

  private final ChatRepository chatRepository;
  private final ChatOccurrenceExceptionRepository exceptionRepository;
  private final ChatOccurrenceProjector projector = new ChatOccurrenceProjector();

  public List<ChatOccurrence> getOccurrences(
      Long seriesId, LocalDateTime from, LocalDateTime to, int limit) {
    if (from == null || to == null || !from.isBefore(to)) {
      throw new BadRequestException("Occurrence window must have a start before its end");
    }
    if (limit < 1 || limit > 100) {
      throw new BadRequestException("Occurrence projection limit must be between 1 and 100");
    }
    var series =
        chatRepository
            .findById(seriesId)
            .orElseThrow(() -> new NotFoundException("Chat Series not found"));
    return projector.project(
        series, exceptionRepository.findBySeries_Id(seriesId), from, to, limit);
  }
}
