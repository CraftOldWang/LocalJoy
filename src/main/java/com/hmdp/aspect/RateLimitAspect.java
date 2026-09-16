package com.hmdp.aspect;

import cn.hutool.core.util.StrUtil;
import com.hmdp.annotation.RateLimit;
import com.hmdp.annotation.RateLimitAlgorithm;
import com.hmdp.annotation.RateLimitScope;
import com.hmdp.dto.UserDTO;
import com.hmdp.exception.RateLimitException;
import com.hmdp.utils.UserHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class RateLimitAspect {

    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT;
    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT;

    static {
        SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>();
        SLIDING_WINDOW_SCRIPT.setLocation(new ClassPathResource("rate_limit_sliding_window.lua"));
        SLIDING_WINDOW_SCRIPT.setResultType(Long.class);

        TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>();
        TOKEN_BUCKET_SCRIPT.setLocation(new ClassPathResource("rate_limit_token_bucket.lua"));
        TOKEN_BUCKET_SCRIPT.setResultType(Long.class);
    }

    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Around("@annotation(com.hmdp.annotation.RateLimit) || " +
            "@annotation(com.hmdp.annotation.RateLimits) || " +
            "@within(com.hmdp.annotation.RateLimit) || " +
            "@within(com.hmdp.annotation.RateLimits)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        List<RateLimit> rateLimits = findRateLimits(joinPoint);
        for (RateLimit rateLimit : rateLimits) {
            checkRateLimit(joinPoint, rateLimit);
        }
        return joinPoint.proceed();
    }

    private void checkRateLimit(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
        String businessKey = resolveBusinessKey(joinPoint, rateLimit);
        for (RateLimitScope scope : rateLimit.scopes()) {
            String key = buildRedisKey(joinPoint, rateLimit, businessKey, scope);
            boolean allowed = rateLimit.algorithm() == RateLimitAlgorithm.TOKEN_BUCKET
                    ? allowByTokenBucket(key, rateLimit)
                    : allowBySlidingWindow(key, rateLimit);
            if (!allowed) {
                throw new RateLimitException(rateLimit.message());
            }
        }
    }

    private boolean allowBySlidingWindow(String key, RateLimit rateLimit) {
        long now = System.currentTimeMillis();
        long windowMillis = Math.max(1L, rateLimit.timeUnit().toMillis(rateLimit.window()));
        Long result = stringRedisTemplate.execute(
                SLIDING_WINDOW_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(now),
                String.valueOf(windowMillis),
                String.valueOf(rateLimit.limit()),
                now + ":" + UUID.randomUUID()
        );
        return Long.valueOf(1L).equals(result);
    }

    private boolean allowByTokenBucket(String key, RateLimit rateLimit) {
        long now = System.currentTimeMillis();
        long refillIntervalMillis = Math.max(1L, rateLimit.refillTimeUnit().toMillis(rateLimit.refillInterval()));
        Long result = stringRedisTemplate.execute(
                TOKEN_BUCKET_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(now),
                String.valueOf(Math.max(1, rateLimit.bucketCapacity())),
                String.valueOf(Math.max(1, rateLimit.refillTokens())),
                String.valueOf(refillIntervalMillis),
                String.valueOf(Math.max(1, rateLimit.permits()))
        );
        return Long.valueOf(1L).equals(result);
    }

    private String buildRedisKey(ProceedingJoinPoint joinPoint, RateLimit rateLimit, String businessKey, RateLimitScope scope) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String name = StrUtil.isBlank(rateLimit.name())
                ? signature.getDeclaringType().getSimpleName() + "." + signature.getMethod().getName()
                : rateLimit.name();
        return "rate_limit:" + rateLimit.algorithm().name().toLowerCase() + ":" + name + ":" + businessKey
                + ":" + scope.name().toLowerCase() + ":" + resolveScopeValue(scope);
    }

    private String resolveScopeValue(RateLimitScope scope) {
        if (scope == RateLimitScope.GLOBAL) {
            return "global";
        }
        if (scope == RateLimitScope.USER) {
            UserDTO user = UserHolder.getUser();
            return user == null ? "anonymous" : String.valueOf(user.getId());
        }
        return getClientIp();
    }

    private String getClientIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes)) {
            return "unknown";
        }
        HttpServletRequest request = ((ServletRequestAttributes) attributes).getRequest();
        // Never trust arbitrary client-supplied forwarding headers. A deployment using a
        // reverse proxy must configure a trusted proxy layer to normalize remoteAddr.
        return request.getRemoteAddr();
    }

    private String resolveBusinessKey(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
        if (StrUtil.isBlank(rateLimit.key())) {
            return "default";
        }
        String expression = rateLimit.key();
        if (!expression.contains("#")) {
            return expression;
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        StandardEvaluationContext context = new StandardEvaluationContext();
        Object[] args = joinPoint.getArgs();
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);
        for (int i = 0; i < args.length; i++) {
            context.setVariable("p" + i, args[i]);
            context.setVariable("a" + i, args[i]);
            if (parameterNames != null && i < parameterNames.length) {
                context.setVariable(parameterNames[i], args[i]);
            }
        }
        Object value = expressionParser.parseExpression(expression).getValue(context);
        return value == null ? "null" : String.valueOf(value);
    }

    private List<RateLimit> findRateLimits(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        List<RateLimit> rateLimits = new ArrayList<>();
        Collections.addAll(rateLimits, joinPoint.getTarget().getClass().getAnnotationsByType(RateLimit.class));
        Collections.addAll(rateLimits, method.getAnnotationsByType(RateLimit.class));
        try {
            Method targetMethod = joinPoint.getTarget().getClass().getMethod(method.getName(), method.getParameterTypes());
            if (!targetMethod.equals(method)) {
                Collections.addAll(rateLimits, targetMethod.getAnnotationsByType(RateLimit.class));
            }
        } catch (NoSuchMethodException ignored) {
        }
        return rateLimits;
    }
}
