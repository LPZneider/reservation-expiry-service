package co.com.nequi.usecase.reservation;

import co.com.nequi.model.exception.OrderNotFoundException;
import co.com.nequi.model.order.Order;
import co.com.nequi.model.order.OrderStatus;
import co.com.nequi.model.order.gateways.OrderRepository;
import co.com.nequi.model.reservation.ReservationExpirationResult;
import co.com.nequi.model.ticket.TicketReleaseResult;
import co.com.nequi.model.ticket.gateways.TicketRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

@RequiredArgsConstructor
public class ExpireReservationUseCase {

    private final TicketRepository ticketRepository;
    private final OrderRepository orderRepository;

    public Mono<ReservationExpirationResult> expire(String orderId, List<String> ticketIds) {
        return orderRepository.findLatestByOrderId(orderId)
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(orderId)))
                .flatMap(order -> isAlreadyResolved(order)
                        ? Mono.just((ReservationExpirationResult) new ReservationExpirationResult.AlreadyProcessed(
                                orderId, order.getOrderStatus()))
                        : processExpiration(order, ticketIds));
    }

    private static boolean isAlreadyResolved(Order order) {
        return order.getOrderStatus() == OrderStatus.CONFIRMED
                || order.getOrderStatus() == OrderStatus.EXPIRED;
    }

    private Mono<ReservationExpirationResult> processExpiration(Order order, List<String> ticketIds) {
        return ticketRepository.releaseAndRestoreAvailability(order.getEventId(), ticketIds, order.getOrderId())
                .flatMap(result -> switch (result) {
                    case TicketReleaseResult.LostRace lr ->
                            Mono.just((ReservationExpirationResult) new ReservationExpirationResult.LostRace(lr.reason()));
                    case TicketReleaseResult.Released ignored ->
                            saveExpiredOrder(order);
                });
    }

    private Mono<ReservationExpirationResult> saveExpiredOrder(Order order) {
        Order expiredOrder = Order.builder()
                .orderId(order.getOrderId())
                .eventId(order.getEventId())
                .ticketIds(order.getTicketIds())
                .userId(order.getUserId())
                .orderStatus(OrderStatus.EXPIRED)
                .createdAt(Instant.now())
                .build();
        return orderRepository.save(expiredOrder)
                .map(saved -> (ReservationExpirationResult) new ReservationExpirationResult.Expired(saved));
    }
}
