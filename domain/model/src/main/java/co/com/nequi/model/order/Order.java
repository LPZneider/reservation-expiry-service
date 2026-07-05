package co.com.nequi.model.order;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

@Value
@Builder
public class Order {
    String orderId;
    String eventId;
    List<String> ticketIds;
    String userId;
    OrderStatus orderStatus;
    Instant createdAt;
}
