
// Toast 
function showToast(message, type = 'default') {
  let container = document.getElementById('toast-container');
  if (!container) {
    container = document.createElement('div');
    container.id = 'toast-container';
    document.body.appendChild(container);
  }
  const toast = document.createElement('div');
  toast.className = `toast-message ${type}`;
  toast.textContent = message;
  container.appendChild(toast);
  requestAnimationFrame(() => toast.classList.add('show'));
  setTimeout(() => {
    toast.classList.remove('show');
    toast.addEventListener('transitionend', () => toast.remove());
  }, 3000);
}

// 渲染結帳資訊
function renderCheckout(checkoutData) {
  const orderDiv = document.getElementById("order-summary");
  orderDiv.innerHTML = "";
  
  const header = document.createElement("div");
  header.className = "order-row order-header";
  header.innerHTML = `<span>商品</span><span>票種</span><span class="text-right">單價</span><span class="text-right">數量</span><span class="text-right">小計</span>`;
  orderDiv.appendChild(header);
  
  checkoutData.order.forEach(i => {
    const row = document.createElement("div");
    row.className = "order-row";
    row.innerHTML = `<span>${i.product}</span><span>${i.type}</span><span class="text-right">$${i.unitprice}</span><span class="text-right">x${i.quantity}</span><span class="text-right"><strong>$${i.subtotal}</strong></span>`;
    orderDiv.appendChild(row);
  });
  
  document.getElementById("order-total").textContent = `$${checkoutData.totalAmount}`;
  const c = checkoutData.customer;
  document.getElementById("cust-name").textContent  = `Name: ${c.name}`;
  document.getElementById("cust-phone").textContent = `Phone: ${c.phone}`;
  document.getElementById("cust-email").textContent = `Email: ${c.email}`;
}

// 初始化
fetch("/api/checkout/summary?t=" + new Date().getTime())
.then(res => {
  if (!res.ok) throw new Error("HTTP " + res.status);
  return res.json();
})
.then(data => {
  // 防止有人打index.html進入，記得找世傑問他的products.html叫啥名稱。先註解掉
  // if (!data.order || data.order.length === 0) {
  //   window.location.href = "/products.html?status=empty";
  //   return;
  // }
  renderCheckout(data);
  document.getElementById('loading-overlay').style.display = 'none';
})
.catch(err => {
  console.error("Failed:", err);
  document.getElementById('loading-overlay').innerHTML = `<p>系統忙碌中，<a href="/products.html">回首頁</a></p>`;
});

// 按鈕事件
document.getElementById("backBtn").onclick = () => window.location.href = "/products.html";


document.getElementById("payBtn").onclick = () => {
  const btn = document.getElementById("payBtn");
  const originalText = btn.textContent;
  
  // 1. 先鎖住按鈕
  btn.disabled = true;
  btn.textContent = "Processing...";

  // 2. 蒐集資料
  const paymentEl = document.querySelector('input[name="pay"]:checked');
  const invoiceEl = document.querySelector('input[name="invoice"]:checked');
  
  // 處理發票值
  let invVal = "";
  if (invoiceEl) {
    if (invoiceEl.value === 'E_INVOICE') {
      // 根據選中的子選項獲取值 (自填信箱/載具碼)
      const einvOptionChecked = document.querySelector('input[name="einv-option"]:checked')?.value;
      if (einvOptionChecked === 'CUSTOM_EMAIL') {
          invVal = document.getElementById('einv-email')?.value;
      } else if (einvOptionChecked === 'CUSTOM_BARCODE') {
          invVal = document.getElementById('einv-barcode')?.value;
      } else {
          invVal = "SAME_AS_USER_EMAIL"; // 預設使用會員信箱
      }
    } else if (invoiceEl.value === 'COMPANY') {
      invVal = document.getElementById('tax-id')?.value;
    } else if (invoiceEl.value === 'DONATION') {
      const donationOptionChecked = document.querySelector('input[name="donation-option"]:checked')?.value;
      if (donationOptionChecked === 'CUSTOM_CODE') {
          invVal = document.getElementById('donation-code')?.value;
      } else {
          invVal = "UNITED_WAY_CODE"; // 預設捐贈碼
      }
    }
  }

  // 蒐集發票細節選項 (einv-option 或 donation-option)
  const einvOptionEl = document.querySelector('input[name="einv-option"]:checked');
  const donationOptionEl = document.querySelector('input[name="donation-option"]:checked');
  const invOptionValue = einvOptionEl ? einvOptionEl.value : (donationOptionEl ? donationOptionEl.value : null);

  const payload = {
    paymentMethod: paymentEl ? paymentEl.value : "",
    atmLast5: document.getElementById('atm-last5')?.value || "",
    invoiceType: invoiceEl ? invoiceEl.value : "",
    invoiceValue: invVal,
    customerEmail: document.getElementById('cust-email').textContent.replace("Email: ", "").trim(),
    invOption: invOptionValue
  };
  
  // 3. 送出請求
  fetch("/api/checkout/submit", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  })
  .then(async res => {
    if (!res.ok) {
      const errData = await res.json();
      throw new Error(errData.message || "結帳失敗");
    }
    return res.json();
  })
  .then(data => {
    console.log("Success:", data);

    // 綠界 (新分頁)
    if (data.message && data.message.startsWith("<form")) {
        const div = document.createElement("div");
        div.innerHTML = data.message;
        div.style.display = 'none';
        document.body.appendChild(div);
        
        const form = document.getElementById('ecpay-form');
        if (form) {
            console.log("開啟綠界新分頁...");
            
            // 設定 target="_blank" 讓它開在新視窗
            form.target = "_blank"; 
            form.submit();

            btn.disabled = false;
            btn.textContent = "已完成付款？點此繼續"; 
            btn.style.backgroundColor = "#28a745"; 
            
            // 綁定新的點擊事件：直接跳轉 success
            btn.onclick = function() {
                window.location.href = "/success.html";
            };
            
            showToast("付款視窗已開啟，請在新視窗完成付款", "success");
        }
        return; 
    }
    
    // 一般成功 (ATM)
    window.location.href = "/success.html"; 
  })
  .catch(err => {
    console.error("Error:", err);
    showToast("沒成功啊 " + err.message, "error");
    btn.disabled = false;
    btn.textContent = originalText;
  });
};

// 驗證邏輯
const payBtn = document.getElementById("payBtn");
const agree = document.getElementById("agree");
const paymentRadios = document.querySelectorAll("input[name='pay']");
const invoiceRadios = document.querySelectorAll("input[name='invoice']");

function isEmailValid(s) { return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s); }

function validate() {
  const selectedPay = document.querySelector('input[name="pay"]:checked');
  const hasPay = !!selectedPay;
  const hasInv = [...invoiceRadios].some(r => r.checked);
  
  let atmValid = true;
  if (selectedPay && selectedPay.value === 'ATM') {
    const last5 = (document.getElementById('atm-last5')?.value || '').trim();
    atmValid = /^\d{5}$/.test(last5);
  }
  
  let invoiceValid = false;
  const invSelected = document.querySelector('input[name="invoice"]:checked');
  if (invSelected) {
    if (invSelected.value === 'E_INVOICE') {
      const opt = document.querySelector('input[name="einv-option"]:checked');
      if (opt) {
        if (opt.value === 'SAME_EMAIL') invoiceValid = true;
        else if (opt.value === 'CUSTOM_EMAIL') {
           invoiceValid = isEmailValid((document.getElementById('einv-email')?.value || '').trim());
        } else if (opt.value === 'CUSTOM_BARCODE') {
           invoiceValid = /^\/[A-Z0-9]{7}$/.test((document.getElementById('einv-barcode')?.value || '').trim().toUpperCase());
        }
      }
    } else if (invSelected.value === 'DONATION') {
      const opt = document.querySelector('input[name="donation-option"]:checked');
      if (opt) {
        if (opt.value === 'UNITED_WAY') invoiceValid = true;
        else if (opt.value === 'CUSTOM_CODE') {
          invoiceValid = /^[0-9A-Za-z\-]{3,20}$/.test((document.getElementById('donation-code')?.value || '').trim());
        }
      }
    } else if (invSelected.value === 'COMPANY') {
      invoiceValid = /^\d{8}$/.test((document.getElementById('tax-id')?.value || '').trim());
    }
  }
  // 注意：如果按鈕被改成「已完成付款」，這裡就不應該再被 validate 覆蓋狀態
  if (payBtn.textContent !== "已完成付款？點此繼續") {
      payBtn.disabled = !(agree.checked && hasPay && hasInv && atmValid && invoiceValid);
  }
}

document.addEventListener("input", validate);

function showOnly(elem) {
  // 修正點：對所有元素進行 null 檢查
  ['einvoice-extra', 'donation-extra', 'company-extra'].forEach(id => {
      const el = document.getElementById(id);
      if (el) el.style.display = 'none';
  });
  if (elem) elem.style.display = 'block';
}

invoiceRadios.forEach(r => {
  r.addEventListener('change', () => {
    if (r.checked) {
      if (r.value === 'E_INVOICE') showOnly(document.getElementById('einvoice-extra'));
      else if (r.value === 'DONATION') showOnly(document.getElementById('donation-extra'));
      else if (r.value === 'COMPANY') showOnly(document.getElementById('company-extra'));
      validate();
    }
  });
});

document.querySelectorAll('input[name="einv-option"]').forEach(rr => {
  rr.addEventListener('change', () => {
    const einvEmailWrap = document.getElementById('einv-email-wrap');
    const einvBarcodeWrap = document.getElementById('einv-barcode-wrap');
    
    // 加入 null 檢查
    if (einvEmailWrap) {
        einvEmailWrap.style.display = (rr.value === 'CUSTOM_EMAIL' && rr.checked) ? 'block' : 'none';
    }
    if (einvBarcodeWrap) {
        einvBarcodeWrap.style.display = (rr.value === 'CUSTOM_BARCODE' && rr.checked) ? 'block' : 'none';
    }
    validate();
  });
});

document.querySelectorAll('input[name="donation-option"]').forEach(rr => {
  rr.addEventListener('change', () => {
    const donationCodeWrap = document.getElementById('donation-code-wrap');
    // 加入 null 檢查
    if (donationCodeWrap) {
        donationCodeWrap.style.display = (rr.value === 'CUSTOM_CODE' && rr.checked) ? 'block' : 'none';
    }
    validate();
  });
});

paymentRadios.forEach(r => {
  r.addEventListener('change', () => {
    const atmExtraEl = document.getElementById('atm-extra'); // 獲取元素
    
    // 檢查元素是否為 null
    if (atmExtraEl) { 
        atmExtraEl.style.display = (r.value === 'ATM' && r.checked) ? 'block' : 'none';
    }
    validate();
  });
});