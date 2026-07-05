package co.com.nequi.model.order.gateways;

import co.com.nequi.model.order.Order;
import reactor.core.publisher.Mono;

public interface OrderRepository {

    Mono<Order> save(Order order);

    Mono<Order> findLatestByOrderId(String orderId);
}
