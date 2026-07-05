package co.com.nequi.sqs.listener;

import co.com.nequi.model.exception.OrderNotFoundException;
import co.com.nequi.model.order.Order;
import co.com.nequi.model.order.OrderStatus;
import co.com.nequi.model.reservation.ReservationExpirationResult;
import co.com.nequi.usecase.reservation.ExpireReservationUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SQSProcessorTest {

    @Mock
    private ExpireReservationUseCase expireReservationUseCase;

    private SQSProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new SQSProcessor(expireReservationUseCase, new ObjectMapper());
    }

    private static Message message(String body) {
        return Message.builder().body(body).build();
    }

    private static final String VALID_BODY = """
            {"orderId":"order-1","ticketIds":["t1","t2"]}
            """;

    @Test
    void shouldCompleteWhenReservationExpired() {
        Order order = Order.builder().orderId("order-1").eventId("event-1").ticketIds(List.of("t1", "t2"))
                .userId("user-1").orderStatus(OrderStatus.EXPIRED).createdAt(Instant.now()).build();
        when(expireReservationUseCase.expire(anyString(), anyList()))
                .thenReturn(Mono.just(new ReservationExpirationResult.Expired(order)));

        StepVerifier.create(processor.apply(message(VALID_BODY)))
                .verifyComplete();
    }

    @Test
    void shouldCompleteWhenLostRace() {
        when(expireReservationUseCase.expire(anyString(), anyList()))
                .thenReturn(Mono.just(new ReservationExpirationResult.LostRace(List.of("t2"))));

        StepVerifier.create(processor.apply(message(VALID_BODY)))
                .verifyComplete();
    }

    @Test
    void shouldCompleteWhenAlreadyProcessed() {
        when(expireReservationUseCase.expire(anyString(), anyList()))
                .thenReturn(Mono.just(new ReservationExpirationResult.AlreadyProcessed("order-1", OrderStatus.CONFIRMED)));

        StepVerifier.create(processor.apply(message(VALID_BODY)))
                .verifyComplete();
    }

    @Test
    void shouldPropagateErrorWhenOrderNotFound() {
        when(expireReservationUseCase.expire(anyString(), anyList()))
                .thenReturn(Mono.error(new OrderNotFoundException("order-1")));

        StepVerifier.create(processor.apply(message(VALID_BODY)))
                .expectError(OrderNotFoundException.class)
                .verify();
    }

    @Test
    void shouldPropagateErrorWhenUseCaseFailsTransiently() {
        when(expireReservationUseCase.expire(anyString(), anyList()))
                .thenReturn(Mono.error(new RuntimeException("DynamoDB throttled")));

        StepVerifier.create(processor.apply(message(VALID_BODY)))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void shouldPropagateErrorForMalformedMessageBody() {
        StepVerifier.create(processor.apply(message("not-json")))
                .expectError()
                .verify();
    }
}
