package de.caritas.cob.userservice.api.service.livechat;

/** A durable claim prevents conversion and irreversible cleanup from crossing. */
public enum LiveChatAccountLifecycle {
  FINISH_PENDING,
  ANONYMOUS_FINISHED,
  PROMOTION_PENDING,
  PROMOTED,
  CLEANUP_STARTED
}
