package com.dduo.notify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 外部 HTTP 通知可靠投递服务(Outbox + 重试 + 死信)。
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class NotifyApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotifyApplication.class, args);
    }
}
