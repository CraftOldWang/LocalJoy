package com.hmdp.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

@Repeatable(RateLimits.class)
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    String name() default "";

    String key() default "";

    RateLimitAlgorithm algorithm() default RateLimitAlgorithm.SLIDING_WINDOW;

    RateLimitScope[] scopes() default {RateLimitScope.IP};

    int limit() default 100;

    long window() default 1;

    TimeUnit timeUnit() default TimeUnit.SECONDS;

    int bucketCapacity() default 100;

    int refillTokens() default 100;

    long refillInterval() default 1;

    TimeUnit refillTimeUnit() default TimeUnit.SECONDS;

    int permits() default 1;

    String message() default "请求过于频繁，请稍后再试";
}
