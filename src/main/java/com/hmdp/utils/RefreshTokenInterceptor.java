package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

//        // 测试模式: Authorization 直接是 userId
//        String authHeader = request.getHeader("Authorization");
//        if (StrUtil.isNotBlank(authHeader)) {
//            String userId = authHeader; // 直接当作 userId
//            String key = RedisConstants.LOGIN_USER_KEY + userId;
//            Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
//
//            // 如果 Redis 中不存在对应用户，直接拒绝
//            if (userMap.isEmpty()) {
//                response.setStatus(HttpServletResponse.SC_FORBIDDEN); // 403 禁止访问
//                response.getWriter().write("非法测试用户: " + userId);
//                return false; // 不放行
//            }
//
//            // Redis 中存在则继续处理
//            UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
//            UserHolder.saveUser(userDTO);
//            stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
//            return true;
//        }


        //1. 从 request header中获取token
        String token = parseBearerToken(request.getHeader("authorization"));
        if (StrUtil.isNotBlank(token) && loadUser(RedisConstants.LOGIN_ACCESS_TOKEN_KEY + token, RedisConstants.LOGIN_ACCESS_TOKEN_TTL)) {
            return true;
        }

        if (StrUtil.isNotBlank(token) && loadUser(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL)) {
            return true;
        }

        HttpSession session = request.getSession(false);
        if (session != null && loadUser(RedisConstants.LOGIN_SESSION_KEY + session.getId(), RedisConstants.LOGIN_SESSION_TTL)) {
            session.setMaxInactiveInterval(Math.toIntExact(TimeUnit.MINUTES.toSeconds(RedisConstants.LOGIN_SESSION_TTL)));
        }

        return true;
    }

    private boolean loadUser(String key, Long ttl) {
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        if (userMap.isEmpty()) {
            return false;
        }
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(userDTO);
        stringRedisTemplate.expire(key, ttl, TimeUnit.MINUTES);
        return true;
    }

    private String parseBearerToken(String token) {
        if (StrUtil.isBlank(token)) {
            return null;
        }
        if (StrUtil.startWithIgnoreCase(token, "Bearer ")) {
            return token.substring(7);
        }
        return token;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.removeUser();
    }

}
