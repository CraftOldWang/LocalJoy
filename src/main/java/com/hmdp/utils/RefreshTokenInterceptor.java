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
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;
        }


        // 2. 得到redis中的用户
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash()
                .entries(key);
        // 3. 用户为空
        if (userMap.isEmpty()) {
            return true;
        }
        // 4. 将hash转换成DTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        // 5. 保存用户
        UserHolder.saveUser(userDTO);

        // 6. 刷新token有效期
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 7. 放行
        return true;
    }


}
