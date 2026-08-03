package de.caritas.cob.userservice.api.service.matrixrtc;

record MatrixRtcPolicyContext(Long tenantId, ChatType chatType) {

  enum ChatType {
    ANONYMOUS,
    ONE_ON_ONE,
    GROUP,
    SUPERVISION
  }
}
