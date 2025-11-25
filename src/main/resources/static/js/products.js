
// Toast

function showToast(message, type = 'default') {
    // 1. 確保容器存在
    let container = document.getElementById('toast-container');
    if (!container) {
        container = document.createElement('div');
        container.id = 'toast-container';
        document.body.appendChild(container);
    }
    
    // 2. 建立 Toast 元素
    const toast = document.createElement('div');
    toast.className = `toast-message ${type}`;
    toast.textContent = message;
    
    // 3. 加入畫面並觸發動畫
    container.appendChild(toast);
    
    // 稍微延遲一下再加 show class，才能觸發 CSS transition
    requestAnimationFrame(() => {
        toast.classList.add('show');
    });
    
    // 4. 設定自動消失 (3秒後)
    setTimeout(() => {
        toast.classList.remove('show');
        // 等動畫跑完再從 DOM 移除
        toast.addEventListener('transitionend', () => {
            toast.remove();
        });
    }, 3000);
}



document.addEventListener("DOMContentLoaded", function() {
    
    const urlParams = new URLSearchParams(window.location.search);
    // ...
    if (urlParams.get('status') === 'empty') {

        setTimeout(() => {
            showToast("購物車是空的，請先選購商品！謝謝", "warning");

            window.history.replaceState(null, null, window.location.pathname);
        }, 100);
    }
    
    // 1. 綁定 UI 事件 (按鈕點擊等) - 這裡只綁定事件，不執行資料載入
    bindUiEvents();
    setupAddCartButtons();
    
    // 2. 全域設定：輸入框限制
    setupInputConstraints();
    
    // 注意：不在這裡呼叫 fetchAndRestoreCart() 了
    // 改由下面的 pageshow 統一處理，避免重複執行
    const checkoutBtn = document.getElementById('cart-btn');
    
    if (checkoutBtn) {
        checkoutBtn.addEventListener('click', function(e) {
            // 1. 去抓目前顯示的數量
            const countText = document.getElementById('cart-count').textContent;
            const totalQty = parseInt(countText) || 0;
            
            // 2. 如果數量是 0，阻止跳轉並警告
            if (totalQty === 0) {
                e.preventDefault(); // 它會阻止 <a href> 的跳轉行為
                setTimeout(() => {
                    showToast("您的購物車是空的，請先選購商品！", "warning");
                    window.history.replaceState(null, null, window.location.pathname);
                }, 100);
            }
        });
    }
});








// 使用 pageshow 確保不管是「第一次進來」還是「按上一頁回來」都會執行
window.addEventListener('pageshow', function(event) {
    console.log("抓到了！pageshow 事件被觸發！isPersisted:", event.persisted);
    
    // 強制重置
    document.querySelectorAll('.qty-input').forEach(el => el.value = 0);
    
    fetchAndRestoreCart();
});


// 強力刷新
function fetchAndRestoreCart() {
    
    // 第一步：先強制把所有輸入框歸零 (避免視覺殘留)
    const allInputs = document.querySelectorAll('.qty-input');
    allInputs.forEach(input => {
        input.value = 0;
    });
    
    // 第二步：呼叫後端 API
    // 加上時間戳記 ?t=... 防止瀏覽器快取 API 結果
    fetch("/api/checkout/my-cart-simple?t=" + new Date().getTime())
    .then(res => res.json())
    .then(cartItems => {
        // cartItems 長這樣: [{ ticket_type_id: 1, quantity: 2 }, ...]
        
        if (cartItems && cartItems.length > 0) {
            allInputs.forEach(input => {
                const inputId = parseInt(input.dataset.id);
                // 找找看這個 ID 有沒有在購物車裡
                const match = cartItems.find(item => item.ticket_type_id === inputId);
                
                if (match) {
                    input.value = match.quantity; // 有就填入數量
                }
            });
        }
        
        // 順便更新右上角按鈕
        updateCartButton();
    })
    .catch(err => console.error("無法回填購物車:", err));
}

// 設定「更新購物車」按鈕監聽
function setupAddCartButtons() {
    const addCartBtns = document.querySelectorAll('.btn-add-cart');
    addCartBtns.forEach(btn => {
        btn.addEventListener('click', function() {
            const originalText = this.textContent;
            this.textContent = "Updating...";
            this.disabled = true;
            
            syncCartLogic(this).then(() => {
                this.textContent = "Updated!";
                setTimeout(() => {
                    this.textContent = originalText;
                    this.disabled = false;
                }, 1000);
            }); 
        });
    });
}

// 同步購物車 (Sync) 
async function syncCartLogic(btn) {
    const card = btn.closest('.product-card');
    const inputs = card.querySelectorAll('.qty-input');
    const itemsToSync = [];
    
    inputs.forEach(input => {
        const qty = parseInt(input.value);
        // 傳送當前輸入框的值 (包含 0)
        itemsToSync.push({
            ticketTypeId: parseInt(input.dataset.id),
            quantity: isNaN(qty) ? 0 : qty
        });
    });
    
    try {
        const promises = itemsToSync.map(item => {
            return fetch("/api/checkout/add", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(item)
            });
        });
        
        await Promise.all(promises);
        updateCartButton(); // 更新右上角
        
    } catch (error) {
        console.error("同步購物車失敗:", error);
        showToast("發生錯誤，請稍後再試。", "error");
    }
}

// 更新右上角按鈕
function updateCartButton() {
    // 同樣加上時間戳記防止快取
    fetch("/api/checkout/summary?t=" + new Date().getTime())
    .then(res => res.ok ? res.json() : null)
    .then(data => {
        if (!data) return;
        const orderList = data.order || [];
        const totalMoney = data.totalAmount || 0;
        let totalQty = 0;
        orderList.forEach(item => totalQty += item.quantity);
        
        const cartInfoSpan = document.getElementById('cart-info');
        const countSpan = document.getElementById('cart-count');
        const totalSpan = document.getElementById('cart-total');
        
        if (totalQty > 0) {
            cartInfoSpan.style.display = 'inline'; 
            countSpan.textContent = totalQty;
            totalSpan.textContent = `$${totalMoney}`;
        } else {
            cartInfoSpan.style.display = 'none';
        }
    })
    .catch(err => console.error("無法更新購物車按鈕:", err));
}

// 彈窗與 UI 綁定
function bindUiEvents() {
    const viewDetailsBtn = document.getElementById('btn-view-details');
    if (viewDetailsBtn) viewDetailsBtn.addEventListener('click', openCartModal);
    
    const closeX = document.getElementById('btn-close-modal-x');
    const closeBtn = document.getElementById('btn-close-modal');
    const modalOverlay = document.getElementById('cart-modal');
    
    if (closeX) closeX.addEventListener('click', closeCartModal);
    if (closeBtn) closeBtn.addEventListener('click', closeCartModal);
    if (modalOverlay) {
        modalOverlay.addEventListener('click', function(e) {
            if (e.target === this) closeCartModal();
        });
    }
}

function setupInputConstraints() {
    const MAX_QUANTITY = 4;
    const allInputs = document.querySelectorAll('.qty-input');
    
    allInputs.forEach(input => {
        input.setAttribute('autocomplete', 'off'); // 再次確保關閉自動填入
        input.value = 0; // 初始歸零
        input.setAttribute('max', MAX_QUANTITY);
        input.addEventListener('input', function() {
            let val = parseInt(this.value);
            if (val > MAX_QUANTITY) this.value = MAX_QUANTITY;
            if (val < 0) this.value = 0;
        });
    });
}

function openCartModal() {
    const modalBody = document.getElementById('modal-body');
    modalBody.innerHTML = '<p style="text-align:center;">載入中...</p>'; 
    document.getElementById('cart-modal').style.display = 'flex';
    
    fetch("/api/checkout/summary?t=" + new Date().getTime())
    .then(res => res.json())
    .then(data => {
        const list = data.order || [];
        if (list.length === 0) {
            modalBody.innerHTML = '<p style="text-align:center; color:#999;">購物車是空的～</p>';
            return;
        }
        
        let html = '';
        list.forEach(item => {
            html += `
                    <div class="cart-item-row">
                        <div style="flex:2;">
                            <strong>${item.product}</strong> <br>
                            <span style="font-size:12px; color:#666;">${item.type}</span>
                        </div>
                        <div style="flex:1; text-align:right;">
                            $${item.unitprice} x ${item.quantity}
                        </div>
                        <div style="flex:1; text-align:right; font-weight:bold;">
                            $${item.subtotal}
                        </div>
                    </div>
                `;
        });
        html += `<div style="margin-top:15px; text-align:right; font-size:18px; font-weight:bold; border-top:2px solid #eee; padding-top:10px;">Total: $${data.totalAmount}</div>`;
        modalBody.innerHTML = html;
    })
    .catch(err => console.error(err));
}

function closeCartModal() {
    document.getElementById('cart-modal').style.display = 'none';
}