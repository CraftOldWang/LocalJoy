package com.hmdp.utils;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // 1.看ThreadLocal中是否有存储用户
        if (UserHolder.getUser() == null) {
            // 2.没有，401未授权， 不放行
            response.setStatus(401);
            return false;
        }
        // 3. 有， 放行
        return true;
    }

}
