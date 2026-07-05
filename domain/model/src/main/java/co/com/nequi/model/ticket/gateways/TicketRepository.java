package co.com.nequi.model.ticket.gateways;

import co.com.nequi.model.ticket.TicketReleaseResult;
import reactor.core.publisher.Mono;

public interface TicketRepository {

    Mono<TicketReleaseResult> conditionalRelease(String eventId, String ticketId, String orderId);
}
