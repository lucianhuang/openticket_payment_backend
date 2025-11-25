
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
    // 回傳型別改成 Map<String, String>
    public Map<String, String> processOrder(CheckoutForm form) {
        
        validateInvoice(form);
        long currentUserId = 1L;

        orderRepository.decreaseStock(currentUserId);
        int totalAmount = orderRepository.calculateTotal(currentUserId);

        PaymentStrategy strategy = strategyMap.get(form.paymentMethod());
        if (strategy == null) {
            throw new RuntimeException("不支援的付款方式: " + form.paymentMethod());
        }

        // 策略回傳的仍然是 String (HTML 代碼 或 狀態字串)
        String result = strategy.pay(form);

        orderRepository.createOrder(currentUserId, form, totalAmount);
        orderRepository.clearCart(currentUserId);

        // 如果結果是 HTML 表單 (綠界)，包進 Map 回傳
        if (result.startsWith("<form")) {
            return Map.of(
                "status", "ecpay",
                "message", result // 前端收到這個會把它塞進 innerHTML
            );
        }

        // 一般 ATM 成功，也用 Map 回傳
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