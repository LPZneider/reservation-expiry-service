package co.com.nequi.model.ticket.gateways;

import co.com.nequi.model.ticket.TicketReleaseResult;
import reactor.core.publisher.Mono;

import java.util.List;

public interface TicketRepository {

    Mono<TicketReleaseResult> releaseAndRestoreAvailability(String eventId, List<String> ticketIds, String orderId);
}
