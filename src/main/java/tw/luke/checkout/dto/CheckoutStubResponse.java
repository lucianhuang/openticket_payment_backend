package tw.luke.checkout.dto;

import java.util.List;

public record CheckoutStubResponse(
        CustomerDto customer,
        List<OrderItemDto> order,
        int totalAmount   
) {}


