package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.MatrixEmailSyncCursor;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatrixEmailSyncCursorRepository
    extends JpaRepository<MatrixEmailSyncCursor, Long> {}
