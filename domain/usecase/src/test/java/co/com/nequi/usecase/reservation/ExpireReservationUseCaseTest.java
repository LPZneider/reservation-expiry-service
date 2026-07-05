package co.com.nequi.usecase.reservation;

import co.com.nequi.model.exception.OrderNotFoundException;
import co.com.nequi.model.order.Order;
import co.com.nequi.model.order.OrderStatus;
import co.com.nequi.model.order.gateways.OrderRepository;
import co.com.nequi.model.reservation.ReservationExpirationResult;
import co.com.nequi.model.ticket.Ticket;
import co.com.nequi.model.ticket.TicketReleaseResult;
import co.com.nequi.model.ticket.TicketStatus;
import co.com.nequi.model.ticket.gateways.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpireReservationUseCaseTest {

    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private OrderRepository orderRepository;

    private ExpireReservationUseCase useCase;

    private static final String ORDER_ID = "order-1";
    private static final String EVENT_ID = "event-1";
    private static final List<String> TICKET_IDS = List.of("t1", "t2");

    @BeforeEach
    void setUp() {
        useCase = new ExpireReservationUseCase(ticketRepository, orderRepository);
    }

    private static Order pendingOrder() {
        return orderWithStatus(OrderStatus.PENDING_CONFIRMATION);
    }

    private static Order orderWithStatus(OrderStatus status) {
        return Order.builder()
                .orderId(ORDER_ID)
                .eventId(EVENT_ID)
                .ticketIds(TICKET_IDS)
                .userId("user-1")
                .orderStatus(status)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void shouldExpireOrderWhenAllTicketsAreReleased() {
        when(orderRepository.findLatestByOrderId(ORDER_ID)).thenReturn(Mono.just(pendingOrder()));
        when(ticketRepository.conditionalRelease(eq(EVENT_ID), anyString(), eq(ORDER_ID)))
                .thenAnswer(invocation -> Mono.just(new TicketReleaseResult.Released(
                        Ticket.builder().ticketId(invocation.getArgument(1)).eventId(EVENT_ID)
                                .status(TicketStatus.AVAILABLE).build())));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(useCase.expire(ORDER_ID, TICKET_IDS))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(ReservationExpirationResult.Expired.class);
                    Order order = ((ReservationExpirationResult.Expired) result).order();
                    assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.EXPIRED);
                    assertThat(order.getOrderId()).isEqualTo(ORDER_ID);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnLostRaceWithoutSavingWhenAnyTicketFailsCondition() {
        when(orderRepository.findLatestByOrderId(ORDER_ID)).thenReturn(Mono.just(pendingOrder()));
        when(ticketRepository.conditionalRelease(EVENT_ID, "t1", ORDER_ID))
                .thenReturn(Mono.just(new TicketReleaseResult.Released(
                        Ticket.builder().ticketId("t1").eventId(EVENT_ID).status(TicketStatus.AVAILABLE).build())));
        when(ticketRepository.conditionalRelease(EVENT_ID, "t2", ORDER_ID))
                .thenReturn(Mono.just(new TicketReleaseResult.LostRace("t2", "already sold by ticket-purchase-service")));

        StepVerifier.create(useCase.expire(ORDER_ID, TICKET_IDS))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(ReservationExpirationResult.LostRace.class);
                    assertThat(((ReservationExpirationResult.LostRace) result).ticketIds()).containsExactly("t2");
                })
                .verifyComplete();

        verify(orderRepository, never()).save(any());
    }

    @Test
    void shouldReturnAlreadyProcessedAndSkipTicketUpdatesWhenOrderIsConfirmed() {
        Order existing = orderWithStatus(OrderStatus.CONFIRMED);
        when(orderRepository.findLatestByOrderId(ORDER_ID)).thenReturn(Mono.just(existing));

        StepVerifier.create(useCase.expire(ORDER_ID, TICKET_IDS))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(ReservationExpirationResult.AlreadyProcessed.class);
                    assertThat(((ReservationExpirationResult.AlreadyProcessed) result).orderStatus())
                            .isEqualTo(OrderStatus.CONFIRMED);
                })
                .verifyComplete();

        verify(ticketRepository, never()).conditionalRelease(anyString(), anyString(), anyString());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void shouldReturnAlreadyProcessedWhenOrderIsRejected() {
        Order existing = orderWithStatus(OrderStatus.REJECTED);
        when(orderRepository.findLatestByOrderId(ORDER_ID)).thenReturn(Mono.just(existing));

        StepVerifier.create(useCase.expire(ORDER_ID, TICKET_IDS))
                .assertNext(result -> assertThat(result).isInstanceOf(ReservationExpirationResult.AlreadyProcessed.class))
                .verifyComplete();

        verify(ticketRepository, never()).conditionalRelease(anyString(), anyString(), anyString());
    }

    @Test
    void shouldReturnAlreadyProcessedWhenOrderIsAlreadyExpired() {
        Order existing = orderWithStatus(OrderStatus.EXPIRED);
        when(orderRepository.findLatestByOrderId(ORDER_ID)).thenReturn(Mono.just(existing));

        StepVerifier.create(useCase.expire(ORDER_ID, TICKET_IDS))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(ReservationExpirationResult.AlreadyProcessed.class);
                    assertThat(((ReservationExpirationResult.AlreadyProcessed) result).orderStatus())
                            .isEqualTo(OrderStatus.EXPIRED);
                })
                .verifyComplete();

        verify(ticketRepository, never()).conditionalRelease(anyString(), anyString(), anyString());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void shouldFailWithOrderNotFoundWhenOrderDoesNotExist() {
        when(orderRepository.findLatestByOrderId(ORDER_ID)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.expire(ORDER_ID, TICKET_IDS))
                .expectError(OrderNotFoundException.class)
                .verify();

        verify(ticketRepository, never()).conditionalRelease(anyString(), anyString(), anyString());
    }

    @Test
    void shouldPropagateTransientErrorWithoutConvertingToLostRace() {
        when(orderRepository.findLatestByOrderId(ORDER_ID)).thenReturn(Mono.just(pendingOrder()));
        when(ticketRepository.conditionalRelease(any(), any(), any()))
                .thenReturn(Mono.error(new RuntimeException("DynamoDB throttled")));

        StepVerifier.create(useCase.expire(ORDER_ID, TICKET_IDS))
                .expectError(RuntimeException.class)
                .verify();

        verify(orderRepository, never()).save(any());
    }
}
