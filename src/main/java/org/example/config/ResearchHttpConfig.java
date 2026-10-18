package org.example.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

/**
 * 检索出站 HTTP 连接工厂。显式声明超时与代理，不依赖 Netty 默认行为：
 * Spring Framework 7 的默认客户端会读 JVM 系统代理属性（IDEA 运行时会注入），
 * 境外线路不稳时一次超时就打死整个 run。默认 noProxy 直连；需要走代理时
 * 用 research.http.proxy-host / proxy-port 显式指定。
 */
@Configuration
public class ResearchHttpConfig {

    @Bean
    public ClientHttpRequestFactory researchHttpRequestFactory(ResearchProperties properties) {
        ResearchProperties.Http settings = properties.effectiveHttp();
        HttpClient client = HttpClient.create()
                .compress(true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) settings.connectTimeout().toMillis())
                .responseTimeout(settings.responseTimeout())
                .secure(spec -> spec.sslContext(Http11SslContextSpec.forClient())
                        .handshakeTimeout(settings.connectTimeout().plusSeconds(5)))
                .followRedirect(true);
        if (settings.proxied()) {
            client = client.proxy(spec -> spec.type(ProxyProvider.Proxy.HTTP)
                    .host(settings.proxyHost())
                    .port(settings.proxyPort()));
        } else {
            client = client.noProxy();
        }
        return new ReactorClientHttpRequestFactory(client);
    }
}
