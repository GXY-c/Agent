package com.gao.agent.service.impl;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class AsyncTaskRunner {

    @Async
    public void executeAsync(Runnable task) {
        task.run();
    }
}
