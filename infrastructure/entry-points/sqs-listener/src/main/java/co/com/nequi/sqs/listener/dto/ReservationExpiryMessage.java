package co.com.nequi.sqs.listener.dto;

import java.util.List;

/**
 * Mirrors the exact shape published by ticket-reservation-service's
 * ReservationExpirySQSAdapter to the reservation-expiry queue: orderId, ticketIds.
 * No eventId here — this use case resolves it from the Order looked up during the
 * idempotency check, not from the message itself.
 */
public record ReservationExpiryMessage(
        String orderId,
        List<String> ticketIds
) {
}
