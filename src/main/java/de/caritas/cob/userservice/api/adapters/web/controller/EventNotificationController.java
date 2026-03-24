package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;

@RestController
@RequestMapping("/users/event-notifications")
@RequiredArgsConstructor
public class EventNotificationController {

  private final @NonNull EventNotificationService eventNotificationService;
  private final @NonNull AuthenticatedUser authenticatedUser;

  @GetMapping
  public ResponseEntity<EventNotificationService.NotificationFeedResponse> getFeed(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) int perPage) {
    return ResponseEntity.ok(
        eventNotificationService.getFeed(authenticatedUser.getUserId(), page, perPage));
  }

  @PatchMapping("/{notificationId}/read")
  public ResponseEntity<Void> markAsRead(@PathVariable Long notificationId) {
    eventNotificationService.markAsRead(authenticatedUser.getUserId(), notificationId);
    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  @PatchMapping("/read-all")
  public ResponseEntity<Void> markAllAsRead() {
    eventNotificationService.markAllAsRead(authenticatedUser.getUserId());
    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  @DeleteMapping
  public ResponseEntity<Void> clear() {
    eventNotificationService.clearFeed(authenticatedUser.getUserId());
    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  @PatchMapping("/active-view")
  public ResponseEntity<Void> updateActiveView(@RequestBody ActiveViewRequestDTO request) {
    boolean active = request == null || request.getActive() == null || request.getActive();
    String roomId = request != null ? request.getRoomId() : null;
    String threadRootId = request != null ? request.getThreadRootId() : null;
    eventNotificationService.updateActiveView(
        authenticatedUser.getUserId(), roomId, threadRootId, active);
    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  @PostMapping("/message-events")
  public ResponseEntity<Void> createMessageEventNotification(
      @RequestBody MessageEventRequestDTO request) {
    if (request == null || request.getRoomId() == null || request.getRoomId().isBlank()) {
      return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
    }

    if (request.getThreadRootId() != null && !request.getThreadRootId().isBlank()) {
      eventNotificationService.createThreadReplyNotificationFromRoom(
          request.getRoomId(),
          authenticatedUser.getUserId(),
          request.getMessagePreview(),
          request.getThreadRootId(),
          request.getMatrixRoom() == null || request.getMatrixRoom(),
          request.getSupervisorMessage() != null && request.getSupervisorMessage(),
          request.getSenderDisplayName(),
          request.getThreadParentPreview());
    } else {
      eventNotificationService.createMessageNotificationFromRoom(
          request.getRoomId(),
          authenticatedUser.getUserId(),
          request.getMessagePreview(),
          request.getMatrixRoom() == null || request.getMatrixRoom(),
          request.getSupervisorMessage() != null && request.getSupervisorMessage(),
          request.getSenderDisplayName());
    }
    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  public static class MessageEventRequestDTO {
    @NotBlank private String roomId;
    private String messagePreview;
    private Boolean matrixRoom;
    private String threadRootId;
    private Boolean supervisorMessage;
    private String senderDisplayName;
    private String threadParentPreview;

    public String getRoomId() {
      return roomId;
    }

    public void setRoomId(String roomId) {
      this.roomId = roomId;
    }

    public String getMessagePreview() {
      return messagePreview;
    }

    public void setMessagePreview(String messagePreview) {
      this.messagePreview = messagePreview;
    }

    public Boolean getMatrixRoom() {
      return matrixRoom;
    }

    public void setMatrixRoom(Boolean matrixRoom) {
      this.matrixRoom = matrixRoom;
    }

    public String getThreadRootId() {
      return threadRootId;
    }

    public void setThreadRootId(String threadRootId) {
      this.threadRootId = threadRootId;
    }

    public String getSenderDisplayName() {
      return senderDisplayName;
    }

    public void setSenderDisplayName(String senderDisplayName) {
      this.senderDisplayName = senderDisplayName;
    }

    public Boolean getSupervisorMessage() {
      return supervisorMessage;
    }

    public void setSupervisorMessage(Boolean supervisorMessage) {
      this.supervisorMessage = supervisorMessage;
    }

    public String getThreadParentPreview() {
      return threadParentPreview;
    }

    public void setThreadParentPreview(String threadParentPreview) {
      this.threadParentPreview = threadParentPreview;
    }
  }

  public static class ActiveViewRequestDTO {
    private String roomId;
    private String threadRootId;
    private Boolean active;

    public String getRoomId() {
      return roomId;
    }

    public void setRoomId(String roomId) {
      this.roomId = roomId;
    }

    public String getThreadRootId() {
      return threadRootId;
    }

    public void setThreadRootId(String threadRootId) {
      this.threadRootId = threadRootId;
    }

    public Boolean getActive() {
      return active;
    }

    public void setActive(Boolean active) {
      this.active = active;
    }
  }
}

