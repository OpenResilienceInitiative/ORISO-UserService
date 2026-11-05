package de.caritas.cob.userservice.api.service.livechat;

/** A durable claim prevents conversion and irreversible cleanup from crossing. */
public enum LiveChatAccountLifecycle {
  PROMOTION_PENDING,
  PROMOTED,
  CLEANUP_STARTED
}
