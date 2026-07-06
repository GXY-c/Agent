package com.gao.agent.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求模型。
 * 用于接收前端提交的用户名和密码，通过 Jakarta Validation 进行参数校验。
 */
public class LoginRequest {

    /** 用户名，不能为空 */
    @NotBlank(message = "username must not be blank")
    private String username;

    /** 密码，不能为空 */
    @NotBlank(message = "password must not be blank")
    private String password;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
