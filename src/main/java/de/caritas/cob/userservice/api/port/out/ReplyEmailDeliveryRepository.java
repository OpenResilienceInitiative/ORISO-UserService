package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ReplyEmailDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplyEmailDeliveryRepository extends JpaRepository<ReplyEmailDelivery, Long> {}
