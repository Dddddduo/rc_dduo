package com.dduo.notify.dispatch;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import com.dduo.notify.config.NotificationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 投递执行池:小规格 + 有界队列。
 * 慢/挂死的供应商只会占住这几个线程,不会拖垮轮询与接口线程(过载隔离)。
 * 队列满时用 CallerRuns 让 dispatcher 自己执行,天然形成背压——
 * 下一轮拉取被拖慢,入库速度自动降下来,而不是无限堆积内存任务。
 */
@Configuration
public class DispatcherExecutorConfig {

    @Bean("deliveryExecutor")
    public Executor deliveryExecutor(NotificationProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int n = props.getDispatch().getMaxThreads();
        executor.setCorePoolSize(n);
        executor.setMaxPoolSize(n);
        executor.setQueueCapacity(props.getDispatch().getQueueCapacity());
        executor.setThreadNamePrefix("delivery-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
