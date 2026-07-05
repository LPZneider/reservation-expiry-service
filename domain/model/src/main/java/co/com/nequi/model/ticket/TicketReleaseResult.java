package co.com.nequi.model.ticket;

public sealed interface TicketReleaseResult {

    record Released(Ticket ticket) implements TicketReleaseResult {
    }

    record LostRace(String ticketId, String reason) implements TicketReleaseResult {
    }
}
