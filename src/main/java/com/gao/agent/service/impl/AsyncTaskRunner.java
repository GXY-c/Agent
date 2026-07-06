package com.gao.agent.service.impl;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 异步任务执行器。
 * 通过 Spring @Async 注解将任务提交到异步线程池执行，
 * 使测试任务的执行不阻塞 HTTP 请求线程，前端可以立即获得 taskId 并通过 SSE 接收进度。
 *
 * 使用方式：在 TestAutomationServiceImpl 中注入本组件，调用 executeAsync(Runnable) 启动任务。
 */
@Component
public class AsyncTaskRunner {

    /**
     * 异步执行给定的任务。
     * Spring AOP 代理会将此方法拦截并提交到异步线程池，调用方线程立即返回。
     *
     * @param task 要异步执行的任务
     */
    @Async
    public void executeAsync(Runnable task) {
        task.run();
    }
}
