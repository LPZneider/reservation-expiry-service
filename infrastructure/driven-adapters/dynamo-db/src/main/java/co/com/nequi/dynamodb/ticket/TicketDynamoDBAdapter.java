package co.com.nequi.dynamodb.ticket;

import co.com.nequi.model.ticket.Ticket;
import co.com.nequi.model.ticket.TicketReleaseResult;
import co.com.nequi.model.ticket.TicketStatus;
import co.com.nequi.model.ticket.gateways.TicketRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.util.Map;

/**
 * Ticket items live in TICKETS_TABLE_NAME: pk=eventId, sk=ticketId (same single-table
 * schema written by ticket-reservation-service). The RESERVED -> AVAILABLE release is a
 * single conditional UpdateItem per ticket; ticket-purchase-service races for the same
 * item with its own ConditionExpression, so exactly one of the two writers wins per
 * ticket via optimistic concurrency — no explicit locking.
 */
@Repository
public class TicketDynamoDBAdapter implements TicketRepository {

    private final DynamoDbAsyncClient client;
    private final String ticketsTableName;

    public TicketDynamoDBAdapter(DynamoDbAsyncClient client,
                                  @Value("${adapter.dynamodb.tickets-table-name}") String ticketsTableName) {
        this.client = client;
        this.ticketsTableName = ticketsTableName;
    }

    @Override
    public Mono<TicketReleaseResult> conditionalRelease(String eventId, String ticketId, String orderId) {
        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(ticketsTableName)
                .key(Map.of(
                        "pk", AttributeValue.fromS(eventId),
                        "sk", AttributeValue.fromS(ticketId)))
                .updateExpression("SET #status = :available REMOVE orderId, reservationExpiresAt")
                .conditionExpression("#status = :reserved AND orderId = :orderId")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(
                        ":available", AttributeValue.fromS(TicketStatus.AVAILABLE.name()),
                        ":reserved", AttributeValue.fromS(TicketStatus.RESERVED.name()),
                        ":orderId", AttributeValue.fromS(orderId)))
                .returnValues(ReturnValue.ALL_NEW)
                .build();

        return Mono.fromFuture(client.updateItem(request))
                .<TicketReleaseResult>map(response -> new TicketReleaseResult.Released(
                        toTicket(response.attributes(), eventId, ticketId)))
                .onErrorResume(ConditionalCheckFailedException.class,
                        ex -> Mono.just(new TicketReleaseResult.LostRace(ticketId,
                                "ticket is no longer RESERVED for this order (already sold or reassigned)")));
    }

    private static Ticket toTicket(Map<String, AttributeValue> attributes, String eventId, String ticketId) {
        return Ticket.builder()
                .ticketId(ticketId)
                .eventId(eventId)
                .status(TicketStatus.valueOf(attributes.get("status").s()))
                .version(attributes.containsKey("version") ? Long.parseLong(attributes.get("version").n()) : 0L)
                .build();
    }
}
