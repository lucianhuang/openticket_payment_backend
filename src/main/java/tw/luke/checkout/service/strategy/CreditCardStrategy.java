package tw.luke.checkout.service.strategy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tw.luke.checkout.dto.CheckoutForm;
import tw.luke.checkout.repository.OrderRepository;
import tw.luke.checkout.service.ECPayService;

@Component("CARD")
public class CreditCardStrategy implements PaymentStrategy {

    @Autowired
    private ECPayService ecPayService;

    @Autowired
    private OrderRepository orderRepository;

    @Override
    public String pay(CheckoutForm form) {
        System.out.println("執行 信用卡/綠界 邏輯：準備產生 HTML 表單...");

        int totalAmount = orderRepository.calculateTotal(1L);

        //
        String htmlForm = ecPayService.genAioCheckOutALL(
            totalAmount, 
            "OpenTicket 票券訂單", 
            "票券交易",
            "Credit" 
        );

        return htmlForm;
    }
}