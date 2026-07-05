package co.com.nequi.dynamodb.ticket;

import co.com.nequi.model.ticket.TicketReleaseResult;
import co.com.nequi.model.ticket.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketDynamoDBAdapterTest {

    @Mock private DynamoDbAsyncClient client;

    private TicketDynamoDBAdapter adapter;

    private static final String EVENT_ID  = "event-1";
    private static final String ORDER_ID  = "order-1";
    private static final List<String> TICKET_IDS = List.of("order-1-1", "order-1-2");

    @BeforeEach
    void setUp() {
        adapter = new TicketDynamoDBAdapter(client, "tickets");
    }

    @Test
    void shouldReturnReleasedWhenTransactionSucceeds() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        StepVerifier.create(adapter.releaseAndRestoreAvailability(EVENT_ID, TICKET_IDS, ORDER_ID))
                .assertNext(result -> assertThat(result).isInstanceOf(TicketReleaseResult.Released.class))
                .verifyComplete();
    }

    @Test
    void shouldBuildTransactionWithNTicketUpdatesPlusOneEventUpdate() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        adapter.releaseAndRestoreAvailability(EVENT_ID, TICKET_IDS, ORDER_ID).block();

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());

        var items = captor.getValue().transactItems();
        // N ticket Updates + 1 Event Update
        assertThat(items).hasSize(TICKET_IDS.size() + 1);

        // First N items: ticket releases
        for (int i = 0; i < TICKET_IDS.size(); i++) {
            var update = items.get(i).update();
            assertThat(update).isNotNull();
            assertThat(update.key().get("pk").s()).isEqualTo(EVENT_ID);
            assertThat(update.key().get("sk").s()).isEqualTo(TICKET_IDS.get(i));
            assertThat(update.conditionExpression()).contains(":reserved");
            assertThat(update.expressionAttributeValues().get(":available").s())
                    .isEqualTo(TicketStatus.AVAILABLE.name());
            assertThat(update.expressionAttributeValues().get(":orderId").s()).isEqualTo(ORDER_ID);
        }

        // Last item: Event availableCount restore
        var eventUpdate = items.get(TICKET_IDS.size()).update();
        assertThat(eventUpdate).isNotNull();
        assertThat(eventUpdate.key().get("sk").s()).isEqualTo("METADATA");
        assertThat(eventUpdate.updateExpression()).contains("availableCount + :qty");
        assertThat(eventUpdate.expressionAttributeValues().get(":qty").n())
                .isEqualTo(String.valueOf(TICKET_IDS.size()));
    }

    @Test
    void shouldReturnLostRaceWithReasonWhenTransactionCancelled() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        TransactionCanceledException.builder().message("ConditionalCheckFailed").build()));

        StepVerifier.create(adapter.releaseAndRestoreAvailability(EVENT_ID, TICKET_IDS, ORDER_ID))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(TicketReleaseResult.LostRace.class);
                    assertThat(((TicketReleaseResult.LostRace) result).reason()).contains(ORDER_ID);
                })
                .verifyComplete();
    }

    @Test
    void shouldWorkWithSingleTicket() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        StepVerifier.create(adapter.releaseAndRestoreAvailability(EVENT_ID, List.of("t1"), ORDER_ID))
                .assertNext(result -> assertThat(result).isInstanceOf(TicketReleaseResult.Released.class))
                .verifyComplete();

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());
        assertThat(captor.getValue().transactItems()).hasSize(2); // 1 ticket + 1 event
    }
}
