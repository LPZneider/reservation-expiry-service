package co.com.nequi.sqs.listener;

import co.com.nequi.model.reservation.ReservationExpirationResult;
import co.com.nequi.sqs.listener.dto.ReservationExpiryMessage;
import co.com.nequi.usecase.reservation.ExpireReservationUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.function.Function;

/**
 * Maps every business outcome (Expired, LostRace, AlreadyProcessed) to a Mono that
 * completes successfully, so the generic SQSListener deletes the message — losing the
 * race against ticket-purchase-service is an expected, resolved outcome, not a
 * processing failure. An OrderNotFoundException (the order should always exist by the
 * time this 600s-delayed message arrives) or any other technical error is left to
 * propagate as Mono.error, so the message is NOT deleted and SQS redelivers it per its
 * visibility timeout, until the DLQ captures it.
 */
@Service
@Log4j2
@RequiredArgsConstructor
public class SQSProcessor implements Function<Message, Mono<Void>> {

    private final ExpireReservationUseCase expireReservationUseCase;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> apply(Message message) {
        return Mono.fromCallable(() -> objectMapper.readValue(message.body(), ReservationExpiryMessage.class))
                .flatMap(this::process)
                .then();
    }

    private Mono<ReservationExpirationResult> process(ReservationExpiryMessage body) {
        return expireReservationUseCase.expire(body.orderId(), body.ticketIds())
                .doOnNext(result -> logResult(body.orderId(), result));
    }

    private void logResult(String orderId, ReservationExpirationResult result) {
        switch (result) {
            case ReservationExpirationResult.Expired expired ->
                    log.info("[EXPIRY] Reservation expired | orderId={}", expired.order().getOrderId());
            case ReservationExpirationResult.LostRace lostRace ->
                    log.info("[EXPIRY] Lost race against ticket-purchase-service, order already sold | "
                            + "orderId={}, reason={}", orderId, lostRace.reason());
            case ReservationExpirationResult.AlreadyProcessed alreadyProcessed ->
                    log.info("[EXPIRY] Order already processed, skipping | orderId={}, status={}",
                            orderId, alreadyProcessed.orderStatus());
        }
    }
}
