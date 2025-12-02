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
    
    
    // 檢查並扣除庫存 (修正版：處理 INT UNSIGNED 轉型問題)
    public void decreaseStock(long userId) {
        // 先把使用者購物車裡的東西撈出來
        String sqlCart = "SELECT event_ticket_type_id, quantity FROM otp.cart_items WHERE user_id = ?";
        List<Map<String, Object>> cartItems = jdbcTemplate.queryForList(sqlCart, userId);
        
        if (cartItems.isEmpty()) {
            throw new RuntimeException("購物車是空的，無法結帳");
        }
        
        // 逐筆檢查庫存
        for (Map<String, Object> item : cartItems) {
            Long eventTicketId = (Long) item.get("event_ticket_type_id"); 
            Integer buyQty = (Integer) item.get("quantity");
            
            // 查詢該票種的限量設定 (is_limited) 和 剩餘數量 (custom_limit) 
            String sqlCheck = "SELECT is_limited, custom_limit FROM otp.event_ticket_type WHERE id = ?";
            Map<String, Object> ticketInfo = jdbcTemplate.queryForMap(sqlCheck, eventTicketId);
            
            // 處理 is_limited
            Object isLimitedObj = ticketInfo.get("is_limited");
            boolean isLimited = false;
            if (isLimitedObj instanceof Number) {
                isLimited = ((Number) isLimitedObj).intValue() == 1;
            } else if (isLimitedObj instanceof Boolean) {
                isLimited = (Boolean) isLimitedObj;
            }
            
            // 取得 custom_limit
            Object limitObj = ticketInfo.get("custom_limit");
            Integer currentLimit = null;
            
            // 先轉成 Number，再取 intValue()，這樣不管是 Long (INT UNSIGNED) 還是 Integer (INT) 都不會報錯
            if (limitObj != null) {
                currentLimit = ((Number) limitObj).intValue();
            }
            
            if (isLimited) {
                // 檢查剩餘數量是否足夠
                if (currentLimit == null || currentLimit < buyQty) {
                    throw new RuntimeException("很抱歉，部分商品庫存不足！(剩餘: " + (currentLimit == null ? 0 : currentLimit) + ")");
                }
                
                // 扣除庫存
                jdbcTemplate.update(
                    "UPDATE otp.event_ticket_type SET custom_limit = custom_limit - ? WHERE id = ?",
                    buyQty, eventTicketId
                );
            }
        }
    }
    
    
    
    
    
    // 計算總金額
    public Integer calculateTotal(long userId) {
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
        
        Double total = jdbcTemplate.queryForObject(sqlTotal, Double.class, userId);
        return total != null ? total.intValue() : 0;
    }
    
    // 建立預約鎖定 (Reservation Lock)
    public void createReservations(long userId) {
        
        // 獲取訂單明細
        // 撈 ett.event_id 以符合 Foreign Key 限制
        String sqlItems = """
            SELECT 
                ci.quantity, 
                ett.id AS event_ticket_type_id,
                ett.event_id, 
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
        
        final int totalQuantity = cartItems.stream().mapToInt(item -> (Integer) item.get("quantity")).sum();
        final Integer totalAmount = calculateTotal(userId); 
        
        // 取得真正的 Event ID (解決 Foreign Key 錯誤)
        final Long realEventId = cartItems.stream()
        .map(item -> (Long) item.get("event_id"))
        .filter(Objects::nonNull) 
        .findFirst()
        .orElseThrow(() -> new RuntimeException("無法從購物車取得有效的活動 ID (Event ID)"));
        
        // 取得票種 ID (作為 ticket_type_id 和 scheduleId 的填充值)
        final Long proxyTicketTypeId = (Long) cartItems.get(0).get("event_ticket_type_id");
        
        // 寫入資料庫 ---
        KeyHolder keyHolder = new GeneratedKeyHolder();
        
        // 針對 reservations 表結構，填入所有 Not Null 欄位
        String insertReservationHeaderSql = """
            INSERT INTO otp.reservations 
            (user_id, event_id, quantity, ticket_type_id, totalAmount, scheduleId, userId, created_at, expires_at, status) 
            VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), DATE_ADD(NOW(), INTERVAL 15 MINUTE), 'LOCKED')
        """;
        
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(insertReservationHeaderSql, Statement.RETURN_GENERATED_KEYS);
            
            // user_id (bigint)
            ps.setLong(1, userId);                 
            
            // event_id (bigint, FK) 
            // 放入從 DB 查出的 realEventId
            ps.setObject(2, realEventId);          
            
            // quantity (int)
            ps.setInt(3, totalQuantity);           
            
            // ticket_type_id (bigint, FK)
            ps.setObject(4, proxyTicketTypeId);    
            
            // totalAmount (int)
            ps.setInt(5, totalAmount);             
            
            // scheduleId (int, Not Null) 
            // 因為沒有 schedule 表，這裡暫時填入票種 ID 的整數值以避免報錯
            ps.setInt(6, proxyTicketTypeId.intValue()); 
            
            // userId (int, Not Null) 
            // 這是表中的第 14 欄，注意它是 INT 型別，需轉型
            ps.setInt(7, (int) userId);                 
            
            return ps;
        }, keyHolder);
        
        long reservationId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        
        // 預約鎖定明細表 (otp.reservation_items)
        String insertReservationItemSql = """
            INSERT INTO otp.reservation_items 
            (reservationId, ticketTypeId, quantity, unitPrice) 
            VALUES (?, ?, ?, ?)
        """;
        
        for (Map<String, Object> item : cartItems) {
            jdbcTemplate.update(insertReservationItemSql,
                reservationId, 
                (Long) item.get("event_ticket_type_id"), 
                (Integer) item.get("quantity"),
                (Integer) ((java.math.BigDecimal) item.get("price_at_purchase")).intValue()
            );
        }
        System.out.println("DEBUG: 成功為 User " + userId + " 創建預約鎖定 ID: " + reservationId);
    }
    
    // 建立正式訂單 (Order)
    public void createOrder(long userId, CheckoutForm form, int totalAmount) {
        
        // 1. 取得 Event ID (假設單筆訂單只對應一個 Event)
        String eventSql = "SELECT distinct ett.event_id FROM otp.cart_items ci JOIN otp.event_ticket_type ett ON ci.event_ticket_type_id = ett.id WHERE ci.user_id = ?";
        List<Long> eventIds = jdbcTemplate.queryForList(eventSql, Long.class, userId);
        final Long eventId = eventIds.isEmpty() ? null : eventIds.get(0); 
        
        // 2. 處理發票和買家資訊 (從資料庫撈 CustomerDto)
        final CustomerDto customer = jdbcTemplate.queryForObject(
            "SELECT account FROM otp.user WHERE id = ?",
            (rs, rowNum) -> {
                String email = rs.getString("account");
                return new CustomerDto("Test User", "0912-345-678", email);
            },
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
            (user_id, event_id, total_amount, status,
             invoice_type, invoice_carrier_type, invoice_carrier_code, invoice_tax_id, invoice_donation_code, invoice_value) 
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        
        // 執行插入並獲取主鍵
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(insertOrderSql, Statement.RETURN_GENERATED_KEYS);
            
            ps.setLong(1, userId);
            ps.setObject(2, eventId);
            ps.setInt(3, totalAmount);
            ps.setString(4, "PENDING"); // status
            ps.setString(5, invType);       // invoice_type
            ps.setObject(6, carrierType);   // invoice_carrier_type
            ps.setObject(7, carrierCode);   // invoice_carrier_code
            ps.setObject(8, taxId);         // invoice_tax_id
            ps.setObject(9, donationCode);  // invoice_donation_code
            ps.setString(10, invVal);       // invoice_value
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
            (order_id, event_ticket_type_id, price_at_purchase, quantity, event_id) 
            VALUES (?, ?, ?, ?, ?)
        """;
        
        for (Map<String, Object> item : cartItems) {
            jdbcTemplate.update(insertItemSql,
                orderId, 
                (Long) item.get("event_ticket_type_id"),
                (java.math.BigDecimal) item.get("price_at_purchase"), 
                (Integer) item.get("quantity"),
                eventId 
            );
        }
    }
    
    // 清空購物車
    public void clearCart(long userId) {
        jdbcTemplate.update("DELETE FROM otp.cart_items WHERE user_id = ?", userId);
    }
    
    
    // 單純檢查庫存是否足夠 (給 Controller 的 Add 使用)
    public boolean checkStock(long ticketTypeId, int requiredQuantity) {
        String sql = "SELECT is_limited, custom_limit FROM otp.event_ticket_type WHERE id = ?";
        
        try {
            Map<String, Object> ticketInfo = jdbcTemplate.queryForMap(sql, ticketTypeId);
            
            // 處理 is_limited
            Object isLimitedObj = ticketInfo.get("is_limited");
            boolean isLimited = false;
            if (isLimitedObj instanceof Number) {
                isLimited = ((Number) isLimitedObj).intValue() == 1;
            } else if (isLimitedObj instanceof Boolean) {
                isLimited = (Boolean) isLimitedObj;
            }
            
            // 如果是無限量，直接回傳 true (庫存充足)
            if (!isLimited) {
                return true;
            }
            
            // 處理 custom_limit (安全轉型)
            Object limitObj = ticketInfo.get("custom_limit");
            Integer currentStock = null;
            if (limitObj != null) {
                currentStock = ((Number) limitObj).intValue();
            }
            
            // 判斷庫存
            // 如果庫存是 null 或是 庫存 < 需要的數量，代表不足
            if (currentStock == null || currentStock < requiredQuantity) {
                return false; 
            }
            
            return true; // 庫存足夠
            
        } catch (Exception e) {
            // 如果查不到該票種，視為庫存不足或錯誤
            return false;
        }
    }
}