package co.com.nequi.dynamodb.order;

import co.com.nequi.model.order.Order;
import co.com.nequi.model.order.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PagePublisher;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderDynamoDBAdapterTest {

    @Mock
    private DynamoDbEnhancedAsyncClient client;
    @Mock
    private DynamoDbAsyncTable<OrderEntity> table;

    private OrderDynamoDBAdapter adapter;

    @BeforeEach
    void setUp() {
        when(client.table("orders", TableSchema.fromBean(OrderEntity.class))).thenReturn(table);
        adapter = new OrderDynamoDBAdapter(client, "orders");
    }

    @Test
    void shouldAppendNewItemPerOrderStateTransition() {
        Order order = Order.builder()
                .orderId("order-1")
                .eventId("event-1")
                .ticketIds(List.of("t1", "t2"))
                .userId("user-1")
                .orderStatus(OrderStatus.EXPIRED)
                .createdAt(Instant.parse("2026-07-01T10:10:00Z"))
                .build();
        when(table.putItem(any(OrderEntity.class))).thenReturn(CompletableFuture.completedFuture(null));

        StepVerifier.create(adapter.save(order))
                .expectNext(order)
                .verifyComplete();

        ArgumentCaptor<OrderEntity> captor = ArgumentCaptor.forClass(OrderEntity.class);
        verify(table).putItem(captor.capture());
        assertThat(captor.getValue().getPk()).isEqualTo("order-1");
        assertThat(captor.getValue().getSk()).isEqualTo("2026-07-01T10:10:00Z");
        assertThat(captor.getValue().getOrderStatus()).isEqualTo("EXPIRED");
    }

    @Test
    void shouldReturnLatestOrderTransition() {
        OrderEntity entity = new OrderEntity();
        entity.setPk("order-1");
        entity.setSk("2026-07-01T10:10:00Z");
        entity.setOrderId("order-1");
        entity.setEventId("event-1");
        entity.setTicketIds(List.of("t1", "t2"));
        entity.setUserId("user-1");
        entity.setOrderStatus("PENDING_CONFIRMATION");
        entity.setCreatedAt("2026-07-01T10:10:00Z");

        Page<OrderEntity> page = Page.builder(OrderEntity.class).items(List.of(entity)).build();
        PagePublisher<OrderEntity> pagePublisher = PagePublisher.create(SdkPublisher.adapt(Flux.just(page)));
        when(table.query(any(QueryEnhancedRequest.class))).thenReturn(pagePublisher);

        StepVerifier.create(adapter.findLatestByOrderId("order-1"))
                .assertNext(order -> {
                    assertThat(order.getOrderId()).isEqualTo("order-1");
                    assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
                })
                .verifyComplete();
    }

    @Test
    void shouldCompleteEmptyWhenOrderDoesNotExist() {
        Page<OrderEntity> emptyPage = Page.builder(OrderEntity.class).items(List.of()).build();
        PagePublisher<OrderEntity> pagePublisher = PagePublisher.create(SdkPublisher.adapt(Flux.just(emptyPage)));
        when(table.query(any(QueryEnhancedRequest.class))).thenReturn(pagePublisher);

        StepVerifier.create(adapter.findLatestByOrderId("missing"))
                .verifyComplete();
    }
}
