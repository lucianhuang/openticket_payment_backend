package tw.luke.checkout; 
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/health") 
public class DatabaseHealthCheckController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping("/db-status") // 完整路徑：/api/health/db-status
    public String checkDatabaseStatus() {
        
        try {
            Integer status = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            
            if (status != null && status.equals(1)) {
                return "資料庫連線測試成功！MySQL 主機 (" + 
                       jdbcTemplate.getDataSource().getConnection().getMetaData().getURL() + 
                       ") 狀態良好。";
            } else {
                return "連線開啟，但 SELECT 1 查詢結果異常。";
            }
            
        } catch (Exception e) {
            System.err.println("資料庫連線失敗詳情: " + e.getMessage());
            return "資料庫連線失敗！請檢查 application.yml 和網路。錯誤訊息：" + e.getLocalizedMessage();
        }
    }
}