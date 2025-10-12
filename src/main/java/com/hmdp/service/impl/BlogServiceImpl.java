package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import com.sun.org.apache.xpath.internal.operations.Bool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Autowired
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        // 检查...
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {
        // 获取登录用户
        String userId = UserHolder.getUser().getId().toString();
        String key = BLOG_LIKED_KEY + id;
        // 获取blog

        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId);
        if (Boolean.FALSE.equals(isMember)) {
            // 点赞
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForSet().add(key, userId);
            }
        } else {
            // 取消点赞
            stringRedisTemplate.opsForSet().remove(key, userId);
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, userId);
            }
        }

        return Result.ok();
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(this::queryBlogUser);
        records.forEach(this::isBlogLiked);

        return Result.ok(records);
    }

    private void queryBlogUser(Blog blog) {
        User uploader = userService.getById(blog.getUserId());
        blog.setIcon(uploader.getIcon());
        blog.setName(uploader.getNickName());
    }

    private void isBlogLiked(Blog blog) {
        if (UserHolder.getUser() == null) {
            return;
        }
        String userId = UserHolder.getUser().getId().toString();
        Boolean isLike = stringRedisTemplate.opsForSet().isMember(BLOG_LIKED_KEY + blog.getId(), userId);
        blog.setIsLike(Boolean.TRUE.equals(isLike));

    }
}
