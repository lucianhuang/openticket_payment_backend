package tw.luke.checkout.dto;

public record AddToCartForm(
    Long ticketTypeId,  
    Integer quantity 
) {}