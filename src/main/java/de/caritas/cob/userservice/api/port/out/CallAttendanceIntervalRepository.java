package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CallAttendanceInterval;
import de.caritas.cob.userservice.api.model.CallLifecycleProjection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CallAttendanceIntervalRepository
    extends JpaRepository<CallAttendanceInterval, Long> {

  Optional<CallAttendanceInterval> findByCallLifecycleAndMatrixUserIdAndDeviceIdAndLeftAtIsNull(
      CallLifecycleProjection callLifecycle, String matrixUserId, String deviceId);

  List<CallAttendanceInterval> findByCallLifecycle(CallLifecycleProjection callLifecycle);

  List<CallAttendanceInterval> findByCallLifecycleAndLeftAtIsNull(
      CallLifecycleProjection callLifecycle);
}
