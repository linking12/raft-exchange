package com.binance.raftexchange.server.exchange.eventsProcessor;

import com.binance.platform.eventbus.zeromq.ZMQEventBusListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

@Configuration
public class EventbusSenderAutoConfiguration implements ApplicationListener<ApplicationReadyEvent> {
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        ConfigurableEnvironment environment = event.getApplicationContext().getEnvironment();
        String topic = environment.getProperty("raftexchange.eventbus.topic");
        String appName = environment.getProperty("spring.application.name");
        ZMQEventBusListener listener = event.getApplicationContext().getBean(ZMQEventBusListener.class);
        EventBusSender.SENDER = new EventBusSender(listener, topic, appName);
    }
}
