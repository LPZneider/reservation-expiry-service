package co.com.nequi.model.reservation;

import co.com.nequi.model.order.Order;
import co.com.nequi.model.order.OrderStatus;

public sealed interface ReservationExpirationResult {

    record Expired(Order order) implements ReservationExpirationResult {
    }

    record LostRace(String reason) implements ReservationExpirationResult {
    }

    record AlreadyProcessed(String orderId, OrderStatus orderStatus) implements ReservationExpirationResult {
    }
}
