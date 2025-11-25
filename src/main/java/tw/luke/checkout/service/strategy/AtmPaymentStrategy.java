package tw.luke.checkout.service.strategy;

import org.springframework.stereotype.Component;
import tw.luke.checkout.dto.CheckoutForm;

@Component("ATM") // 對應前端 value="ATM"
public class AtmPaymentStrategy implements PaymentStrategy {

    @Override
    public String pay(CheckoutForm form) {
        // 1. 驗證後五碼
        String last5 = form.atmLast5();
        if (last5 == null || !last5.matches("\\d{5}")) {
            throw new RuntimeException("ATM 帳號後五碼錯誤");
        }
        
        System.out.println("執行 ATM 邏輯：檢查通過，等待轉帳...");
        
        // 2. 回傳給 Service 的結果
        return "ATM_ORDER_CREATED";
    }
}