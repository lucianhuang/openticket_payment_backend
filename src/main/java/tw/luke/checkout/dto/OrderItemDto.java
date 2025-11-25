package tw.luke.checkout.dto;

public record OrderItemDto(
        Long id,
        String product,
        String type,
        double unitprice,   
        int quantity,
        double subtotal     
) {}