package tw.luke.checkout.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tw.luke.checkout.dto.*;
import tw.luke.checkout.service.CheckoutService;

import java.io.IOException; 
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/checkout")
public class CheckoutController {

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 1. 加入/更新購物車
    @PostMapping("/add")
    @Transactional
    public String addToCart(@RequestBody AddToCartForm form) {
        if (form.quantity() > 4) throw new RuntimeException("最多 4 張");
        
        long currentUserId = 1L; 
        long ticketTypeId = form.ticketTypeId();
        int newQuantity = form.quantity();

        if (newQuantity <= 0) {
            jdbcTemplate.update("DELETE FROM cart_items WHERE user_id = ? AND ticket_type_id = ?", currentUserId, ticketTypeId);
            return "{\"status\": \"success\", \"message\": \"Item removed\"}";
        }

        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM cart_items WHERE user_id = ? AND ticket_type_id = ?",
            Integer.class, currentUserId, ticketTypeId
        );

        if (count != null && count > 0) {
            jdbcTemplate.update("UPDATE cart_items SET quantity = ? WHERE user_id = ? AND ticket_type_id = ?", newQuantity, currentUserId, ticketTypeId);
        } else {
            jdbcTemplate.update("INSERT INTO cart_items (user_id, ticket_type_id, quantity) VALUES (?, ?, ?)", currentUserId, ticketTypeId, newQuantity);
        }
        return "{\"status\": \"success\", \"message\": \"Cart updated\"}";
    }

    // 2. 取得結帳頁面資訊
    @GetMapping("/summary")
    public CheckoutStubResponse getCheckoutSummary() {
        long currentUserId = 1L;
        
        
        String sql = """
            SELECT 
                ci.id AS cart_id,
                e.title AS product_name,  
                tt_template.name AS ticket_type, 
                ci.quantity,
                CASE
                    WHEN ett.custom_price IS NOT NULL AND ett.custom_price > 0 THEN ett.custom_price
                    ELSE tt_template.price
                END AS unit_price
            FROM otp.cart_items ci
            JOIN otp.event_ticket_type ett ON ci.event_ticket_type_id = ett.id
            JOIN otp.ticket_type tt_template ON ett.ticket_template_id = tt_template.id
            JOIN otp.event e ON ett.event_id = e.id
            WHERE ci.user_id = ?
        """;

        List<OrderItemDto> order = jdbcTemplate.query(sql, (rs, rowNum) -> {
            double price = rs.getDouble("unit_price");
            int qty = rs.getInt("quantity");
            
            return new OrderItemDto(
                rs.getLong("cart_id"),
                rs.getString("product_name"), 
                rs.getString("ticket_type"), 
                price,                        
                qty,
                price * qty // 由於 price 和 subtotal 已經是 double，這裡的計算結果也是 double
            );
        }, currentUserId);

        CustomerDto customer = jdbcTemplate.queryForObject(
                "SELECT email FROM otp.user WHERE id = ?", 
                (rs, rowNum) -> {
                    String email = rs.getString("email");
                    // 這裡使用假資料 ("Stub Name", "0912-345-678") 來滿足 CustomerDto 的結構
                    return new CustomerDto("Test User", "0912-345-678", email);
                },
                currentUserId
        );

        int totalAmount = (int) order.stream().mapToDouble(OrderItemDto::subtotal).sum();
        return new CheckoutStubResponse(customer, order, totalAmount);
    }
    
    // 輔助 API
    @GetMapping("/my-cart-simple")
    public List<Map<String, Object>> getMyCartSimple() {
        return jdbcTemplate.queryForList("SELECT ticket_type_id, quantity FROM cart_items WHERE user_id = ?", 1L);
    }

    // 3. 送出訂單 (給前端 JS 呼叫)
    @PostMapping("/submit")
    public Map<String, String> submitOrder(@RequestBody CheckoutForm form) {
        return checkoutService.processOrder(form);
    }

    // 4. 綠界交易
    @RequestMapping(value = "/ecpay-return", method = {RequestMethod.POST, RequestMethod.GET})
    public void ecpayReturn(HttpServletResponse response) throws IOException {
        System.out.println("收到綠界回傳 (POST/GET)，準備跳轉 success.html");
        response.sendRedirect("/success.html");
    }
}