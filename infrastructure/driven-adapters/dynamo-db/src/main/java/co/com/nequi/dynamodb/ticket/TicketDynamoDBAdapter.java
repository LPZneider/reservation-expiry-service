package co.com.nequi.dynamodb.ticket;

import co.com.nequi.model.ticket.TicketReleaseResult;
import co.com.nequi.model.ticket.TicketStatus;
import co.com.nequi.model.ticket.gateways.TicketRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Release is a single TransactWriteItems:
 *   - N Deletes: each ticket RESERVED -> deleted (condition: status=RESERVED AND orderId=:orderId)
 *   - 1 Update on Event: availableCount += quantity
 * Either everything commits or nothing does.
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
    public Mono<TicketReleaseResult> releaseAndRestoreAvailability(String eventId, List<String> ticketIds, String orderId) {
        int quantity = ticketIds.size();
        List<TransactWriteItem> items = new ArrayList<>();

        // 1. Delete each RESERVED ticket (condition: status=RESERVED AND orderId=:orderId)
        for (String ticketId : ticketIds) {
            items.add(TransactWriteItem.builder()
                    .delete(Delete.builder()
                            .tableName(ticketsTableName)
                            .key(Map.of(
                                    "pk", AttributeValue.fromS(eventId),
                                    "sk", AttributeValue.fromS(ticketId)))
                            .conditionExpression("#status = :reserved AND orderId = :orderId")
                            .expressionAttributeNames(Map.of("#status", "status"))
                            .expressionAttributeValues(Map.of(
                                    ":reserved", AttributeValue.fromS(TicketStatus.RESERVED.name()),
                                    ":orderId",  AttributeValue.fromS(orderId)))
                            .build())
                    .build());
        }

        // 2. Restore availableCount on Event
        items.add(TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(ticketsTableName)
                        .key(Map.of(
                                "pk", AttributeValue.fromS(eventId),
                                "sk", AttributeValue.fromS("METADATA")))
                        .updateExpression("SET availableCount = availableCount + :qty")
                        .expressionAttributeValues(Map.of(
                                ":qty", AttributeValue.fromN(String.valueOf(quantity))))
                        .build())
                .build());

        return Mono.fromFuture(client.transactWriteItems(
                        TransactWriteItemsRequest.builder().transactItems(items).build()))
                .<TicketReleaseResult>map(r -> new TicketReleaseResult.Released())
                .onErrorResume(TransactionCanceledException.class,
                        ex -> Mono.just(new TicketReleaseResult.LostRace(
                                "tickets for order " + orderId + " already resolved (sold or reassigned)")));
    }
}
