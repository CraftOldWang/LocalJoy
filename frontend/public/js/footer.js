Vue.component('footBar', {
  props: ['activeBtn'],
  template: `<nav class="foot" aria-label="主要导航">
    <a class="foot-box" :class="{active: activeBtn === 1}" href="/index.html"><div class="foot-view"><i class="el-icon-s-home" aria-hidden="true"></i></div><div class="foot-text">发现</div></a>
    <a class="foot-box" :class="{active: activeBtn === 2}" href="/products.html"><div class="foot-view"><i class="el-icon-shopping-bag-1" aria-hidden="true"></i></div><div class="foot-text">限时好物</div></a>
    <a class="foot-box" href="/blog-edit.html" aria-label="发布笔记"><img class="add-btn" src="/imgs/add.png" alt="发布笔记"></a>
    <a class="foot-box" :class="{active: activeBtn === 3}" href="/orders.html"><div class="foot-view"><i class="el-icon-tickets" aria-hidden="true"></i></div><div class="foot-text">订单</div></a>
    <a class="foot-box" :class="{active: activeBtn === 4}" href="/info.html"><div class="foot-view"><i class="el-icon-user" aria-hidden="true"></i></div><div class="foot-text">我的</div></a>
  </nav>`
});
