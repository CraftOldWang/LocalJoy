package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginTokenDTO {

    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private Long expiresIn;
}
