package tw.luke.checkout.repository;

import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tw.luke.checkout.dto.CheckoutForm;
import tw.luke.checkout.dto.CustomerDto;

import java.util.List;
import java.util.Map;

@Repository
public class OrderRepository {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    // 1. 檢查並扣除庫存
    public void decreaseStock(long userId) {
        // 撈出購物車內容，FK 欄位名已改為 event_ticket_type_id)
        String sqlCart = "SELECT event_ticket_type_id, quantity FROM otp.cart_items WHERE user_id = ?";
        List<Map<String, Object>> cartItems = jdbcTemplate.queryForList(sqlCart, userId);
        
        // 除錯
        System.out.println("DEBUG: User " + userId + " 正在結帳，購物車內有 " + cartItems.size() + " 筆商品");
        if (cartItems.isEmpty()) {
            throw new RuntimeException("購物車是空的，無法結帳");
        }
        
        // 迴圈檢查並扣庫存 (從 otp.event_ticket_type.custom_limit 檢查)
        for (Map<String, Object> item : cartItems) {
            // 這裡的 Long 是 Long，但在資料庫中可能設置為 INT 或 BIGINT
            // 為了避免 ClassCastException，請確保資料庫的 ID 欄位與此處類型匹配
            Long eventTicketId = (Long) item.get("event_ticket_type_id"); 
            Integer buyQty = (Integer) item.get("quantity");
            
            // 檢查庫存 (從 otp.event_ticket_type.custom_limit 檢查)
            Integer currentLimit = jdbcTemplate.queryForObject(
                "SELECT custom_limit FROM otp.event_ticket_type WHERE id = ?", 
                Integer.class, eventTicketId
            );
            
            // 如果 custom_limit 是 NULL (代表無限量)，或庫存不足
            if (currentLimit != null && currentLimit < buyQty) {
                throw new RuntimeException("很抱歉，部分商品庫存不足！(剩餘: " + currentLimit + ")");
            }
            
            // 扣除庫存 (只在 custom_limit 非 NULL 時才扣除)
            if (currentLimit != null) {
                jdbcTemplate.update(
                    "UPDATE otp.event_ticket_type SET custom_limit = custom_limit - ? WHERE id = ?",
                    buyQty, eventTicketId
                );
            }
        }
    }
    
    // 2. 計算總金額，使用三層聯結和價格 CASE 邏輯
    public Integer calculateTotal(long userId) {
        // 使用 Double 進行計算，避免 DECIMAL 轉 INT 錯誤
        String sqlTotal = """
            SELECT COALESCE(SUM(
                ci.quantity * CASE
                    WHEN ett.custom_price IS NOT NULL AND ett.custom_price > 0 THEN ett.custom_price
                    ELSE tt_template.price
                END
            ), 0)
            FROM otp.cart_items ci
            JOIN otp.event_ticket_type ett ON ci.event_ticket_type_id = ett.id
            JOIN otp.ticket_type tt_template ON ett.ticket_template_id = tt_template.id
            WHERE ci.user_id = ?
        """;
        
        // 接收結果為 Double，再轉為 Integer，因為 CheckoutService 預期 Integer
        Double total = jdbcTemplate.queryForObject(sqlTotal, Double.class, userId);
        return total != null ? total.intValue() : 0;
    }
    
    // 1.5. 建立預約鎖定 (Reservation Lock)
    public void createReservations(long userId) {
        
        // 1. 獲取訂單明細 (需要 event_ticket_type_id 和 quantity)
        String sqlItems = """
            SELECT 
                ci.quantity, ett.id AS event_ticket_type_id,
                CASE
                    WHEN ett.custom_price IS NOT NULL AND ett.custom_price > 0 THEN ett.custom_price
                    ELSE tt_template.price
                END AS price_at_purchase
            FROM otp.cart_items ci
            JOIN otp.event_ticket_type ett ON ci.event_ticket_type_id = ett.id
            JOIN otp.ticket_type tt_template ON ett.ticket_template_id = tt_template.id
            WHERE ci.user_id = ?
        """;
        List<Map<String, Object>> cartItems = jdbcTemplate.queryForList(sqlItems, userId);
        
        if (cartItems.isEmpty()) return;

        // 2. 插入【預約鎖定主表】(otp.reservations) 並獲取 ID (使用 KeyHolder)
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String insertReservationHeaderSql = """
            INSERT INTO otp.reservations 
            (userId, created_at, expires_at, status) 
            VALUES (?, NOW(), DATE_ADD(NOW(), INTERVAL 15 MINUTE), 'LOCKED')
        """;
        // 鎖15分鐘這裡，要改可從這裡調整
        
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(insertReservationHeaderSql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            return ps;
        }, keyHolder);

        long reservationId = Objects.requireNonNull(keyHolder.getKey()).longValue();

        // 3. 插入【預約鎖定明細表】(otp.reservation_items)
        String insertReservationItemSql = """
            INSERT INTO otp.reservation_items 
            (reservationId, ticketTypeId, quantity, unitPrice) 
            VALUES (?, ?, ?, ?)
        """;
        
        for (Map<String, Object> item : cartItems) {
            jdbcTemplate.update(insertReservationItemSql,
                reservationId, // 外鍵
                (Long) item.get("event_ticket_type_id"), // ⚠️ 這裡使用 event_ticket_type_id 作為 ticketTypeId
                (Integer) item.get("quantity"),
                (Integer) ((java.math.BigDecimal) item.get("price_at_purchase")).intValue() // ⚠️ 價格轉換為 INT
            );
        }
        System.out.println("DEBUG: 成功為 User " + userId + " 創建預約鎖定 ID: " + reservationId);
    }





    public void createOrder(long userId, CheckoutForm form, int totalAmount) {
        
        // 1. 取得 Event ID (假設單筆訂單只對應一個 Event)
        String eventSql = "SELECT distinct ett.event_id FROM otp.cart_items ci JOIN otp.event_ticket_type ett ON ci.event_ticket_type_id = ett.id WHERE ci.user_id = ?";
        List<Long> eventIds = jdbcTemplate.queryForList(eventSql, Long.class, userId);
        final Long eventId = eventIds.isEmpty() ? null : eventIds.get(0); 
        
        // 2. 處理發票和買家資訊 (從資料庫撈 CustomerDto)
        final CustomerDto customer = jdbcTemplate.queryForObject( // 設為 final
            "SELECT email FROM otp.user WHERE id = ?", 
            (rs, rowNum) -> new CustomerDto("Test User", "0912-345-678", rs.getString("email")),
            userId
        );
        
        final String invType = form.invoiceType();
        final String invOpt = form.invOption(); 
        final String invVal = form.invoiceValue();
        final String carrierType;
        final String carrierCode;
        final String taxId;
        final String donationCode;
        
        if ("E_INVOICE".equals(invType)) {
            carrierType = ("CUSTOM_BARCODE".equals(invOpt) || "SAME_EMAIL".equals(invOpt) || "CUSTOM_EMAIL".equals(invOpt)) ? ("CUSTOM_BARCODE".equals(invOpt) ? "Mobile Barcode" : "Email") : null;
            carrierCode = "CUSTOM_BARCODE".equals(invOpt) ? invVal : null;
            taxId = null;
            donationCode = null;
        } else if ("COMPANY".equals(invType)) {
            carrierType = null;
            carrierCode = null;
            taxId = invVal;
            donationCode = null;
        } else if ("DONATION".equals(invType)) {
            carrierType = null;
            carrierCode = null;
            taxId = null;
            donationCode = invVal;
        } else {
            carrierType = null;
            carrierCode = null;
            taxId = null;
            donationCode = null;
        }
        
        // 4. 插入訂單主表(otp.orders) 並獲取 ID
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String insertOrderSql = """
        INSERT INTO otp.orders 
        (user_id, event_id, total_amount, status, buyer_email, buyer_name, buyer_phone,
         invoice_type, invoice_carrier_type, invoice_carrier_code, invoice_tax_id, invoice_donation_code, invoice_value) 
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """;
        
        // 執行插入並獲取主鍵
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(insertOrderSql, Statement.RETURN_GENERATED_KEYS);
            // 參數數量必須是 13 個
            ps.setLong(1, userId);
            ps.setObject(2, eventId);
            ps.setInt(3, totalAmount);
            ps.setString(4, "PENDING"); // status
            ps.setString(5, customer.email()); // buyer_email
            ps.setString(6, customer.name()); // buyer_name
            ps.setString(7, customer.phone()); // buyer_phone
            
            // 這裡使用的變數因為在外部沒有被重新賦值，所以是 "effectively final"
            ps.setString(8, invType);       // invoice_type
            ps.setObject(9, carrierType);   // invoice_carrier_type
            ps.setObject(10, carrierCode);  // invoice_carrier_code
            ps.setObject(11, taxId);        // invoice_tax_id
            ps.setObject(12, donationCode); // invoice_donation_code
            ps.setString(13, invVal);       // invoice_value (統編/載具碼/捐贈碼)
            return ps;
        }, keyHolder);
        
        // 5. 獲取訂單 ID
        long orderId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        
        // 6. 插入【訂單明細表】(otp.checkout_orders) (價格快照)
        String sqlItems = """
            SELECT 
                ci.quantity, ett.id AS event_ticket_type_id,
                CASE
                    WHEN ett.custom_price IS NOT NULL AND ett.custom_price > 0 THEN ett.custom_price
                    ELSE tt_template.price
                END AS price_at_purchase
            FROM otp.cart_items ci
            JOIN otp.event_ticket_type ett ON ci.event_ticket_type_id = ett.id
            JOIN otp.ticket_type tt_template ON ett.ticket_template_id = tt_template.id
            WHERE ci.user_id = ?
        """;
        
        List<Map<String, Object>> cartItems = jdbcTemplate.queryForList(sqlItems, userId);
        
        String insertItemSql = """
            INSERT INTO otp.checkout_orders 
            (order_id, event_ticket_type_id, price_at_purchase, quantity) 
            VALUES (?, ?, ?, ?)
        """;
        
        for (Map<String, Object> item : cartItems) {
            jdbcTemplate.update(insertItemSql,
                orderId, 
                (Long) item.get("event_ticket_type_id"),
                (java.math.BigDecimal) item.get("price_at_purchase"), // 價格是 DECIMAL
                (Integer) item.get("quantity")
            );
        }
    }
    
    // 清空購物車
    public void clearCart(long userId) {
        jdbcTemplate.update("DELETE FROM otp.cart_items WHERE user_id = ?", userId);
    }
}