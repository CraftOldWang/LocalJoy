package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

@Component
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        //1. 从session中获取用户
        HttpSession session = request.getSession();
        User user = (User) session.getAttribute("user");

        //2. 判断用户是否存在
        if (user == null) {
            // 3. 用户不存在， 拦截
            response.setStatus(401);
            return false;
        }

        //4. 若用户存在， 保存用户
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setIcon(user.getIcon());
        userDTO.setNickName(user.getNickName());
        UserHolder.saveUser(userDTO);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
