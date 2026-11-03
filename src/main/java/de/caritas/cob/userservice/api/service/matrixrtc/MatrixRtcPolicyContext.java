package de.caritas.cob.userservice.api.service.matrixrtc;

/**
 * What the call policy needs to know about the conversation behind a Matrix room.
 *
 * @param consultingTypeId the form of counselling the conversation belongs to; {@code null} for a
 *     supervision room (staff to staff, not gated by it) or when it cannot be told
 */
record MatrixRtcPolicyContext(Long tenantId, ChatType chatType, Integer consultingTypeId) {

  enum ChatType {
    ANONYMOUS,
    ONE_ON_ONE,
    GROUP,
    SUPERVISION
  }
}
