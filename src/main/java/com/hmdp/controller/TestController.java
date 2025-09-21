package com.hmdp.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Slf4j
@Controller
@RequestMapping("/test")
@ResponseBody // This annotation returns the string directly as the response body
public class TestController {

    @GetMapping("hello") // Relative path
    public String sayHello() {
        log.info("This is the relative path: /test/hello");
        return "This is the relative path: /test/hello";
    }

    @GetMapping("/world") // Absolute path
    public String sayWorld() {
        log.info("This is the absolute path: /world");

        return "This is the absolute path: /world";
    }
}
