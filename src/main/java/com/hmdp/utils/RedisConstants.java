package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 30L;
    public static final String LOGIN_SESSION_KEY = "login:session:";
    public static final Long LOGIN_SESSION_TTL = 30L;
    public static final String LOGIN_ACCESS_TOKEN_KEY = "login:access:";
    public static final Long LOGIN_ACCESS_TOKEN_TTL = 30L;
    public static final String LOGIN_REFRESH_TOKEN_KEY = "login:refresh:";
    public static final Long LOGIN_REFRESH_TOKEN_TTL = 7L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String CACHE_SHOPTYPE_KEY = "cache:typelist";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    public static final String SECKILL_ORDER_CLOSE_KEY = "seckill:order:closed:";
    public static final String PRODUCT_SECKILL_STOCK_KEY = "seckill:product:stock:";
    public static final String PRODUCT_SECKILL_BUYER_KEY = "seckill:product:buyer:";
    public static final String PRODUCT_SECKILL_META_KEY = "seckill:product:meta:";
    public static final String CACHE_PRODUCT_KEY = "cache:product:";
    public static final String LOCK_PRODUCT_CACHE_KEY = "lock:cache:product:";
    public static final String PRODUCT_SECKILL_ORDER_KEY = "seckill:product:order:";
    public static final String PRODUCT_SECKILL_ORDER_CLOSE_KEY = "seckill:product:order:closed:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

}
