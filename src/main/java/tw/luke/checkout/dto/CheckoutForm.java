package tw.luke.checkout.dto;

// 這是用來對應前端送過來的 JSON 欄位
public record CheckoutForm(
    String paymentMethod,
    String atmLast5,
    String invoiceType,
    String invoiceValue,
    String customerEmail,
    String invOption      
) {}