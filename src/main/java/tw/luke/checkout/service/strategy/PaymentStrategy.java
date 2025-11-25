package tw.luke.checkout.service.strategy;

import tw.luke.checkout.dto.CheckoutForm;

public interface PaymentStrategy {
    // 定義共同行為：每個策略都要能「付錢」
    // 回傳 String 是為了之後可能要回傳綠界的 HTML form
    String pay(CheckoutForm form);
}