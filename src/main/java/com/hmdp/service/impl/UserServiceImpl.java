package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.LoginTokenDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.PasswordEncoder;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }

        //2. 生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 3. 保存验证码到Redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code);
        stringRedisTemplate.expire(LOGIN_CODE_KEY + phone, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.debug("发送短信验证码成功，验证码：{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        User user = checkLoginUser(loginForm);
        if (user == null) {
            return Result.fail("手机号、验证码或密码错误");
        }

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        String token = UUID.randomUUID().toString();
        cacheUser(LOGIN_USER_KEY + token, userDTO, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(token);
    }

    @Override
    public Result loginBySession(LoginFormDTO loginForm, HttpSession session) {
        User user = checkLoginUser(loginForm);
        if (user == null) {
            return Result.fail("手机号、验证码或密码错误");
        }

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        session.setAttribute("userId", userDTO.getId());
        session.setMaxInactiveInterval(Math.toIntExact(TimeUnit.MINUTES.toSeconds(LOGIN_SESSION_TTL)));
        cacheUser(LOGIN_SESSION_KEY + session.getId(), userDTO, LOGIN_SESSION_TTL, TimeUnit.MINUTES);

        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", session.getId());
        return Result.ok(data);
    }

    @Override
    public Result loginByDoubleToken(LoginFormDTO loginForm) {
        User user = checkLoginUser(loginForm);
        if (user == null) {
            return Result.fail("手机号、验证码或密码错误");
        }

        return Result.ok(createTokenPair(user));
    }

    @Override
    public Result refreshToken(String refreshToken) {
        refreshToken = parseBearerToken(refreshToken);
        if (StrUtil.isBlank(refreshToken)) {
            return Result.fail("refreshToken不能为空");
        }
        String refreshKey = LOGIN_REFRESH_TOKEN_KEY + refreshToken;
        String userId = stringRedisTemplate.opsForValue().get(refreshKey);
        if (StrUtil.isBlank(userId)) {
            return Result.fail("refreshToken无效或已过期");
        }
        User user = getById(Long.valueOf(userId));
        if (user == null) {
            stringRedisTemplate.delete(refreshKey);
            return Result.fail("用户不存在");
        }
        stringRedisTemplate.delete(refreshKey);
        return Result.ok(createTokenPair(user));
    }

    @Override
    public Result logout(HttpServletRequest request) {
        String accessToken = parseBearerToken(request.getHeader("authorization"));
        if (StrUtil.isNotBlank(accessToken)) {
            stringRedisTemplate.delete(LOGIN_USER_KEY + accessToken);
            stringRedisTemplate.delete(LOGIN_ACCESS_TOKEN_KEY + accessToken);
        }
        String refreshToken = parseBearerToken(request.getHeader("refresh-token"));
        if (StrUtil.isNotBlank(refreshToken)) {
            stringRedisTemplate.delete(LOGIN_REFRESH_TOKEN_KEY + refreshToken);
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            stringRedisTemplate.delete(LOGIN_SESSION_KEY + session.getId());
            session.invalidate();
        }
        UserHolder.removeUser();
        return Result.ok();
    }

    private User checkLoginUser(LoginFormDTO loginForm) {
        String phone = loginForm.getPhone();

        // 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return null;
        }

        String code = loginForm.getCode();
        String password = loginForm.getPassword();
        boolean codeLogin = StrUtil.isNotBlank(code);
        if (codeLogin) {
            String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
            // 之前这里的条件应当是 cacheCode == null 而不是 code == null ... 写错了； 调用equals 或者toString 是需要不为空的
            if (cacheCode == null || !cacheCode.equals(code)) {
                return null;
            }
        } else if (StrUtil.isBlank(password)) {
            return null;
        }

        // 查询用户
        User user = query().eq("phone", phone).one();

        // 无则创建
        if (user == null) {
            if (!codeLogin) {
                return null;
            }
            user = createUserWithPhone(phone);
        }

        if (!codeLogin && !PasswordEncoder.matches(user.getPassword(), password)) {
            return null;
        }
        return user;
    }

    private LoginTokenDTO createTokenPair(User user) {
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        String accessToken = UUID.randomUUID().toString();
        String refreshToken = UUID.randomUUID().toString();
        cacheUser(LOGIN_ACCESS_TOKEN_KEY + accessToken, userDTO, LOGIN_ACCESS_TOKEN_TTL, TimeUnit.MINUTES);
        stringRedisTemplate.opsForValue().set(LOGIN_REFRESH_TOKEN_KEY + refreshToken, user.getId().toString(),
                LOGIN_REFRESH_TOKEN_TTL, TimeUnit.DAYS);
        return new LoginTokenDTO(accessToken, refreshToken, "Bearer",
                TimeUnit.MINUTES.toSeconds(LOGIN_ACCESS_TOKEN_TTL));
    }

    private void cacheUser(String key, UserDTO userDTO, Long ttl, TimeUnit unit) {
        // 转换成按Hash存进去
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );
        stringRedisTemplate.opsForHash().putAll(key, userMap);
        stringRedisTemplate.expire(key, ttl, unit);
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
    public Result sign() {
        //1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        //3. 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4. 获取今天是当月第几天(1~31)
        int dayOfMonth = now.getDayOfMonth();
        //5. 写入Redis  BITSET key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        //3. 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4. 获取今天是当月第几天(1~31)
        int dayOfMonth = now.getDayOfMonth();
        //5. 获取截止至今日的签到记录  BITFIELD key GET uDay 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create()
                .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        //6. 循环遍历
        int count = 0;
        Long num = result.get(0);
        while ((num & 1) != 0) {
            count++;
            //数字右移，抛弃最后一位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {

        // 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

        // 保存用户
        save(user);

        return user;
    }
}
