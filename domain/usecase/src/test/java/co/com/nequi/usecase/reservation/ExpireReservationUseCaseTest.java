package co.com.nequi.usecase.reservation;

import co.com.nequi.model.exception.OrderNotFoundException;
import co.com.nequi.model.order.Order;
import co.com.nequi.model.order.OrderStatus;
import co.com.nequi.model.order.gateways.OrderRepository;
import co.com.nequi.model.reservation.ReservationExpirationResult;
import co.com.nequi.model.ticket.TicketReleaseResult;
import co.com.nequi.model.ticket.gateways.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpireReservationUseCaseTest {

    @Mock private TicketRepository ticketRepository;
    @Mock private OrderRepository orderRepository;

    private ExpireReservationUseCase useCase;

    private static final String ORDER_ID  = "order-1";
    private static final String EVENT_ID  = "event-1";
    private static final List<String> TICKET_IDS = List.of("order-1-1", "order-1-2");

    @BeforeEach
    void setUp() {
        useCase = new ExpireReservationUseCase(ticketRepository, orderRepository);
    }

    private static Order orderWithStatus(OrderStatus status) {
        return Order.builder().orderId(ORDER_ID).eventId(EVENT_ID).ticketIds(TICKET_IDS)
                .userId("user-1").orderStatus(status).createdAt(Instant.now()).build();
    }

    @Test
    void shouldExpireOrderAndRestoreAvailabilityWhenTransactionSucceeds() {
        when(orderRepository.findLatestByOrderId(ORDER_ID))
                .thenReturn(Mono.just(orderWithStatus(OrderStatus.PENDING_CONFIRMATION)));
        when(ticketRepository.releaseAndRestoreAvailability(anyString(), anyList(), anyString()))
                .thenReturn(Mono.just(new TicketReleaseResult.Released()));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(useCase.expire(ORDER_ID, TICKET_IDS))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(ReservationExpirationResult.Expired.class);
                    Order saved = ((ReservationExpirationResult.Expired) result).order();
                    assertThat(saved.getOrderStatus()).isEqualTo(OrderStatus.EXPIRED);
                    assertThat(saved.getOrderId()).isEqualTo(ORDER_ID);
                })
                .verifyComplete();
    }

    @Test
    void shouldPassCorrectArgumentsToReleaseAndRestoreAvailability() {
        when(orderRepository.findLatestByOrderId(ORDER_ID))
                .thenReturn(Mono.just(orderWithStatus(OrderStatus.PENDING_CONFIRMATION)));
        when(ticketRepository.releaseAndRestoreAvailability(anyString(), anyList(), anyString()))
                .thenReturn(Mono.just(new TicketReleaseResult.Released()));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        useCase.expire(ORDER_ID, TICKET_IDS).block();

        ArgumentCaptor<String> eventCaptor   = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List>   ticketCaptor  = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> orderCaptor   = ArgumentCaptor.forClass(String.class);
        verify(ticketRepository).releaseAndRestoreAvailability(
                eventCaptor.capture(), ticketCaptor.capture(), orderCaptor.capture());
        assertThat(eventCaptor.getValue()).isEqualTo(EVENT_ID);
        assertThat(ticketCaptor.getValue()).isEqualTo(TICKET_IDS);
        assertThat(orderCaptor.getValue()).isEqualTo(ORDER_ID);
    }

    @Test
    void shouldReturnLostRaceWithoutSavingWhenTransactionCancelled() {
        when(orderRepository.findLatestByOrderId(ORDER_ID))
                .thenReturn(Mono.just(orderWithStatus(OrderStatus.PENDING_CONFIRMATION)));
        when(ticketRepository.releaseAndRestoreAvailability(anyString(), anyList(), anyString()))
                .thenReturn(Mono.just(new TicketReleaseResult.LostRace("already sold")));

        StepVerifier.create(useCase.expire(ORDER_ID, TICKET_IDS))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(ReservationExpirationResult.LostRace.class);
                    assertThat(((ReservationExpirationResult.LostRace) result).reason())
                            .isEqualTo("already sold");
                })
                .verifyComplete();

        verify(orderRepository, never()).save(any());
    }

    @Test
    void shouldReturnAlreadyProcessedAndSkipReleaseWhenOrderIsConfirmed() {
        when(orderRepository.findLatestByOrderId(ORDER_ID))
                .thenReturn(Mono.just(orderWithStatus(OrderStatus.CONFIRMED)));

        StepVerifier.create(useCase.expire(ORDER_ID, TICKET_IDS))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(ReservationExpirationResult.AlreadyProcessed.class);
                    assertThat(((ReservationExpirationResult.AlreadyProcessed) result).orderStatus())
                            .isEqualTo(OrderStatus.CONFIRMED);
                })
                .verifyComplete();

        verify(ticketRepository, never()).releaseAndRestoreAvailability(anyString(), anyList(), anyString());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void shouldExpireTicketsWhenOrderIsRejected() {
        when(orderRepository.findLatestByOrderId(ORDER_ID))
                .thenReturn(Mono.just(orderWithStatus(OrderStatus.REJECTED)));
        when(ticketRepository.releaseAndRestoreAvailability(anyString(), anyList(), anyString()))
                .thenReturn(Mono.just(new TicketReleaseResult.Released()));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(useCase.expire(ORDER_ID, TICKET_IDS))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(ReservationExpirationResult.Expired.class);
                    Order saved = ((ReservationExpirationResult.Expired) result).order();
                    assertThat(saved.getOrderStatus()).isEqualTo(OrderStatus.EXPIRED);
                })
                .verifyComplete();

        verify(ticketRepository).releaseAndRestoreAvailability(anyString(), anyList(), anyString());
    }

    @Test
    void shouldReturnAlreadyProcessedWhenOrderIsAlreadyExpired() {
        when(orderRepository.findLatestByOrderId(ORDER_ID))
                .thenReturn(Mono.just(orderWithStatus(OrderStatus.EXPIRED)));

        StepVerifier.create(useCase.expire(ORDER_ID, TICKET_IDS))
                .assertNext(result -> assertThat(result)
                        .isInstanceOf(ReservationExpirationResult.AlreadyProcessed.class))
                .verifyComplete();

        verify(ticketRepository, never()).releaseAndRestoreAvailability(anyString(), anyList(), anyString());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void shouldFailWithOrderNotFoundWhenOrderDoesNotExist() {
        when(orderRepository.findLatestByOrderId(ORDER_ID)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.expire(ORDER_ID, TICKET_IDS))
                .expectError(OrderNotFoundException.class)
                .verify();

        verify(ticketRepository, never()).releaseAndRestoreAvailability(anyString(), anyList(), anyString());
    }

    @Test
    void shouldPropagateTransientErrorFromRepository() {
        when(orderRepository.findLatestByOrderId(ORDER_ID))
                .thenReturn(Mono.just(orderWithStatus(OrderStatus.PENDING_CONFIRMATION)));
        when(ticketRepository.releaseAndRestoreAvailability(anyString(), anyList(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("DynamoDB throttled")));

        StepVerifier.create(useCase.expire(ORDER_ID, TICKET_IDS))
                .expectError(RuntimeException.class)
                .verify();

        verify(orderRepository, never()).save(any());
    }
}
