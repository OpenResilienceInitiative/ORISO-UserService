package de.caritas.cob.userservice.api.service.matrixrtc;

/**
 * What the call policy needs to know about the conversation behind a Matrix room.
 *
 * @param consultingTypeId the form of counselling the conversation belongs to; {@code null} when
 *     there is none to tell
 * @param followsConsultingType whether the form of counselling decides if calls are offered. False
 *     for a supervision room (staff to staff) and for a team discussion whose parent session is
 *     gone: there is nothing to read, and such a room was never closed by it.
 */
record MatrixRtcPolicyContext(
    Long tenantId, ChatType chatType, Integer consultingTypeId, boolean followsConsultingType) {

  enum ChatType {
    ANONYMOUS,
    ONE_ON_ONE,
    GROUP,
    SUPERVISION
  }
}
