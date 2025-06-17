package com.project.docs.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Component
@RequiredArgsConstructor
@Slf4j
public class websocketeventlistener
{
    @EventListener
    public void handledisconnect(SessionDisconnectEvent event)
    {
        //implement later
    }
    
}
