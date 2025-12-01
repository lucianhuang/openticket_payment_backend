package tw.luke.checkout.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tw.luke.checkout.dto.CheckoutForm;
import tw.luke.checkout.repository.OrderRepository;
import tw.luke.checkout.service.strategy.PaymentStrategy;

import java.util.Map;

@Service
public class CheckoutService {
    
    private final Map<String, PaymentStrategy> strategyMap;
    private final OrderRepository orderRepository;
    
    @Autowired
    public CheckoutService(Map<String, PaymentStrategy> strategyMap, OrderRepository orderRepository) {
        this.strategyMap = strategyMap;
        this.orderRepository = orderRepository;
    }
    
    @Transactional
    public Map<String, String> processOrder(CheckoutForm form) {
        
        validateInvoice(form);
        long currentUserId = 1L;
        
        // 1. 【創建預約鎖定】 (防止超賣，最優先執行) 關鍵在這
        orderRepository.createReservations(currentUserId); 
        
        // 2. 【扣除庫存】 (鎖定成功後，再實際扣除庫存)
        orderRepository.decreaseStock(currentUserId);
        
        // 3. 【計算總金額】
        int totalAmount = orderRepository.calculateTotal(currentUserId);
        
        // 4. 【執行支付策略】 (會根據選擇的支付方式回傳 HTML 或狀態)
        PaymentStrategy strategy = strategyMap.get(form.paymentMethod());
        if (strategy == null) {
            throw new RuntimeException("不支援的付款方式: " + form.paymentMethod());
        }
        String result = strategy.pay(form);
        
        // 5. 【創建主訂單及明細】 (必須在支付策略執行後，因為需要總金額)
        orderRepository.createOrder(currentUserId, form, totalAmount);
        
        // 6. 【清空購物車】 (交易完成)
        orderRepository.clearCart(currentUserId);
        
        // 7. 【回傳結果】
        if (result.startsWith("<form")) {
            return Map.of(
                "status", "ecpay",
                "message", result // 回傳 HTML 給前端跳轉
            );
        }
        
        // 一般成功 (ATM)
        return Map.of(
            "status", "success", 
            "message", "訂單成功"
        );
    }
    
    
    
    
    
    
    private void validateInvoice(CheckoutForm form) {
        if (form.paymentMethod() == null || form.paymentMethod().isEmpty()) {
            throw new RuntimeException("請選擇付款方式");
        }
        String type = form.invoiceType();
        String rawValue = form.invoiceValue();
        if (type == null || type.isEmpty()) throw new RuntimeException("請選擇發票類型");
        
        if ("COMPANY".equals(type) && (rawValue == null || !rawValue.matches("\\d{8}"))) {
            throw new RuntimeException("統一編號格式錯誤 (需為 8 碼數字)");
        } else if ("E_INVOICE".equals(type) && (rawValue == null || rawValue.trim().isEmpty())) {
            throw new RuntimeException("請填寫手機載具或 Email");
        } else if ("DONATION".equals(type) && (rawValue == null || rawValue.trim().isEmpty())) {
            throw new RuntimeException("請填寫捐贈碼");
        }
    }
}