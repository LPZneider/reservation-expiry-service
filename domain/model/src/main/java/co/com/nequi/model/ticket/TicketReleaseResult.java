package co.com.nequi.model.ticket;

public sealed interface TicketReleaseResult {

    record Released() implements TicketReleaseResult {
    }

    record LostRace(String reason) implements TicketReleaseResult {
    }
}
