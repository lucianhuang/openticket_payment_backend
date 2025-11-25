package tw.luke.checkout.service.strategy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tw.luke.checkout.dto.CheckoutForm;
import tw.luke.checkout.repository.OrderRepository;
import tw.luke.checkout.service.ECPayService;

@Component("LINEPAY") // 讓Spring知道這個策略對應前端的 value="LINEPAY"
public class LinePayStrategy implements PaymentStrategy {

    @Autowired
    private ECPayService ecPayService;

    @Autowired
    private OrderRepository orderRepository;

    @Override
    public String pay(CheckoutForm form) {
        System.out.println("執行 LINE Pay 邏輯：準備產生 HTML 表單...");

        int totalAmount = orderRepository.calculateTotal(1L);

        // 使用 Credit 支付代碼，讓綠界處理跳轉，所以看起來跟信用卡一樣 = =
        String htmlForm = ecPayService.genAioCheckOutALL(
            totalAmount, 
            "OpenTicket LINE Pay 訂單", 
            "LINE Pay 交易",
            "Credit" 
        );
        
        return htmlForm;
    }
}