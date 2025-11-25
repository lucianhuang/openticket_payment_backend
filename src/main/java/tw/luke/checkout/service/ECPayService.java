package tw.luke.checkout.service;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class ECPayService {

    @Value("${ecpay.merchant-id}")
    private String merchantId;

    @Value("${ecpay.hash-key}")
    private String hashKey;

    @Value("${ecpay.hash-iv}")
    private String hashIv;

    @Value("${ecpay.api-url}")
    private String apiUrl;
    
    @Value("${ecpay.client-back-url}")
    private String clientBackUrl;

    // 注入在 YML 設定的網域名稱
    @Value("${app.domain}")
    private String domain;

    /**
     * 產生綠界需要的 HTML 表單
     * @param totalAmount 訂單總金額
     * @param itemName 商品名稱
     * @param tradeDesc 交易描述
     * @param choosePayment 綠界支付方式代碼 (Credit, LINEPAY, ATM, etc.)
     * @return 綠界支付 HTML 表單字串
     */
    public String genAioCheckOutALL(int totalAmount, String itemName, String tradeDesc, String choosePayment) {
        
        // 1. 產生不重複的訂單編號
        String tradeNo = "Tkt" + System.currentTimeMillis(); 
        String tradeDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));

        // 2. 準備參數 (使用 TreeMap 自動依 Key 排序)
        Map<String, String> params = new TreeMap<>();
        params.put("MerchantID", merchantId);
        params.put("MerchantTradeNo", tradeNo);
        params.put("MerchantTradeDate", tradeDate);
        params.put("PaymentType", "aio");
        params.put("TotalAmount", String.valueOf(totalAmount));
        params.put("TradeDesc", tradeDesc);
        params.put("ReturnURL", domain + "/api/checkout/ecpay-return");
        params.put("ClientBackURL", clientBackUrl);
        params.put("ItemName", itemName);
        
        // 只有當 choosePayment 非空時才將其加入 params
        if (choosePayment != null && !choosePayment.isEmpty()) {
            params.put("ChoosePayment", choosePayment); 
        }
        
        params.put("EncryptType", "1"); // SHA256

        // 3. 產生檢查碼
        String checkMacValue = generateCheckMacValue(params);
        params.put("CheckMacValue", checkMacValue);

        // 4. 產生 HTML Form
        StringBuilder html = new StringBuilder();
        html.append("<form id='ecpay-form' action='").append(apiUrl).append("' method='POST'>");
        
        for (Map.Entry<String, String> entry : params.entrySet()) {
            html.append("<input type='hidden' name='").append(entry.getKey())
                .append("' value='").append(entry.getValue()).append("'>");
        }
        
        html.append("</form>");
        html.append("<script>document.getElementById('ecpay-form').submit();</script>");

        return html.toString();
    }

    // 綠界檢查碼演算法 (保持不變)
    private String generateCheckMacValue(Map<String, String> params) {
        String raw = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));

        raw = "HashKey=" + hashKey + "&" + raw + "&HashIV=" + hashIv;

        String urlEncoded = URLEncoder.encode(raw, StandardCharsets.UTF_8).toLowerCase();

        urlEncoded = urlEncoded.replace("%2d", "-")
                               .replace("%5f", "_")
                               .replace("%2e", ".")
                               .replace("%21", "!")
                               .replace("%2a", "*")
                               .replace("%28", "(")
                               .replace("%29", ")")
                               .replace("%20", "+");

        return DigestUtils.sha256Hex(urlEncoded).toUpperCase();
    }
}