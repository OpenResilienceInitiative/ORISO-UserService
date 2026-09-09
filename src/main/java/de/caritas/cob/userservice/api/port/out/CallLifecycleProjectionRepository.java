package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CallLifecycleProjection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CallLifecycleProjectionRepository
    extends JpaRepository<CallLifecycleProjection, Long> {

  Optional<CallLifecycleProjection> findByMatrixRoomIdAndCallId(String matrixRoomId, String callId);

  Optional<CallLifecycleProjection> findByCallRoomIdAndCallId(String callRoomId, String callId);

  List<CallLifecycleProjection> findByMatrixRoomId(String matrixRoomId);

  List<CallLifecycleProjection> findByCallRoomId(String callRoomId);
}
