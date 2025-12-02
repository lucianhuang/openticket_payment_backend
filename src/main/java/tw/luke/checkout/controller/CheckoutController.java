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
    
    @Autowired
    private tw.luke.checkout.repository.OrderRepository orderRepository;
    
    // 加入/更新購物車
    @PostMapping("/add")
    @Transactional
    public String addToCart(@RequestBody AddToCartForm form) {
        long currentUserId = 1L; 
        long ticketTypeId = form.ticketTypeId();
        int quantityToAdd = form.quantity();
        
        // 防呆
        if (quantityToAdd <= 0) {
            jdbcTemplate.update("DELETE FROM cart_items WHERE user_id = ? AND event_ticket_type_id = ?", currentUserId, ticketTypeId);
            return "{\"status\": \"success\", \"message\": \"Item removed\"}";
        }
        
        // 計算「預期總數量」 (原本購物車有的 + 這次要加的)
        // 先查購物車現在有幾張 (如果沒有就是 0)
        String sqlCount = "SELECT COALESCE(SUM(quantity), 0) FROM cart_items WHERE user_id = ? AND event_ticket_type_id = ?";
        Integer currentCartQty = jdbcTemplate.queryForObject(sqlCount, Integer.class, currentUserId, ticketTypeId);
        
        int finalQuantity = currentCartQty + quantityToAdd;
        
        // 檢查規則 ：單次限購規則 (例如總數不能超過 4) 
        // 這裡假設要檢查總數
        if (finalQuantity > 4) {
            throw new RuntimeException("單一票種每人限購 4 張");
        }
        
        // 檢查規則 ：資料庫庫存檢查 (Call Repository) 
        // KEY point
        // 呼叫 checkStock
        boolean isStockEnough = orderRepository.checkStock(ticketTypeId, finalQuantity);
        
        if (!isStockEnough) {
            throw new RuntimeException("庫存不足！無法加入購物車");
        }
        
        // 執行加入/更新
        if (currentCartQty > 0) {
            jdbcTemplate.update("UPDATE cart_items SET quantity = ? WHERE user_id = ? AND event_ticket_type_id = ?", finalQuantity, currentUserId, ticketTypeId);
        } else {
            jdbcTemplate.update("INSERT INTO cart_items (user_id, event_ticket_type_id, quantity) VALUES (?, ?, ?)", currentUserId, ticketTypeId, finalQuantity);
        }
        
        return "{\"status\": \"success\", \"message\": \"Cart updated\"}";
    }
    
    // 取得結帳頁面資訊
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
        return jdbcTemplate.queryForList("SELECT event_ticket_type_id AS ticket_type_id, quantity FROM cart_items WHERE user_id = ?", 1L);
    }
    
    // 送出訂單 (給前端 JS 呼叫)
    @PostMapping("/submit")
    public Map<String, String> submitOrder(@RequestBody CheckoutForm form) {
        return checkoutService.processOrder(form);
    }
    
    // 綠界交易
    @RequestMapping(value = "/ecpay-return", method = {RequestMethod.POST, RequestMethod.GET})
    public void ecpayReturn(HttpServletResponse response) throws IOException {
        System.out.println("收到綠界回傳 (POST/GET)，準備跳轉 success.html");
        response.sendRedirect("/success.html");
    }
}