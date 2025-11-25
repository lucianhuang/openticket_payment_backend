
document.addEventListener("DOMContentLoaded", function() {
    
    console.log("Order Success Page Initialized.");

    const backButton = document.querySelector('.btn-primary'); 
    
    if (backButton) {
        // 確保按鈕連結到正確的目標
        backButton.href = "javascript:void(0)"; // 移除 HTML 上的 href
        backButton.addEventListener('click', function(e) {
            e.preventDefault();
            window.location.href = "/index.html"; // 這段記得找世傑拿
        });

        backButton.textContent = "回結帳頁面";
    }
});