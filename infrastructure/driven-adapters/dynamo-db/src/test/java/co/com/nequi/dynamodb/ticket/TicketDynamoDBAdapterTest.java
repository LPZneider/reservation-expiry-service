package co.com.nequi.dynamodb.ticket;

import co.com.nequi.model.ticket.TicketReleaseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketDynamoDBAdapterTest {

    @Mock
    private DynamoDbAsyncClient client;

    private TicketDynamoDBAdapter adapter;

    private static final String EVENT_ID = "event-1";
    private static final String TICKET_ID = "t1";
    private static final String ORDER_ID = "order-1";

    @BeforeEach
    void setUp() {
        adapter = new TicketDynamoDBAdapter(client, "tickets");
    }

    @Test
    void shouldReturnReleasedWhenConditionalUpdateSucceeds() {
        UpdateItemResponse response = UpdateItemResponse.builder()
                .attributes(Map.of(
                        "status", AttributeValue.fromS("AVAILABLE"),
                        "version", AttributeValue.fromN("2")))
                .build();
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(CompletableFuture.completedFuture(response));

        StepVerifier.create(adapter.conditionalRelease(EVENT_ID, TICKET_ID, ORDER_ID))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(TicketReleaseResult.Released.class);
                    var released = (TicketReleaseResult.Released) result;
                    assertThat(released.ticket().getTicketId()).isEqualTo(TICKET_ID);
                    assertThat(released.ticket().getEventId()).isEqualTo(EVENT_ID);
                })
                .verifyComplete();

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(client).updateItem(captor.capture());
        assertThat(captor.getValue().tableName()).isEqualTo("tickets");
        assertThat(captor.getValue().key().get("pk").s()).isEqualTo(EVENT_ID);
        assertThat(captor.getValue().key().get("sk").s()).isEqualTo(TICKET_ID);
    }

    @Test
    void shouldReturnLostRaceWhenConditionFails() {
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(ConditionalCheckFailedException.builder().build()));

        StepVerifier.create(adapter.conditionalRelease(EVENT_ID, TICKET_ID, ORDER_ID))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(TicketReleaseResult.LostRace.class);
                    assertThat(((TicketReleaseResult.LostRace) result).ticketId()).isEqualTo(TICKET_ID);
                })
                .verifyComplete();
    }
}
