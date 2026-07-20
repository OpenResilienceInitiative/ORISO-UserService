package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.model.Chat.ChatModality;
import java.time.LocalDateTime;

public record ChatOccurrence(
    long seriesId,
    int occurrenceIndex,
    LocalDateTime originalStart,
    LocalDateTime start,
    int duration,
    Integer capacity,
    ChatModality modality) {}
