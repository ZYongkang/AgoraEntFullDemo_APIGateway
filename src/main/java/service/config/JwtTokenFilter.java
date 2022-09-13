package service.config;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import service.common.BaseResult;
import service.utils.JwtUtil;
import service.utils.VoiceRoomJwtUtil;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class JwtTokenFilter implements GlobalFilter , Ordered {
    private String[] skipAuthUrls =
            {"/api-login/users/verificationCode", "/api-login/users/login", "/user/login/device"};
 

    @Resource
    private StringRedisTemplate redisTemplate;

    @Resource
    private JwtUtil jwtUtil;

    @Value("${jwt.token.exp-time}")
    private String exTime;

    @Resource
    private VoiceRoomJwtUtil voiceRoomJwtUtil;

    /**
     * 过滤器
     *
     * @param exchange
     * @param chain
     * @return
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String url = exchange.getRequest().getURI().getPath();
        //跳过不需要验证的路径
        if (null != skipAuthUrls && Arrays.asList(skipAuthUrls).contains(url)) {
            return chain.filter(exchange);
        }
        //获取token
        String token = exchange.getRequest().getHeaders().getFirst("Authorization");
        ServerHttpResponse resp = exchange.getResponse();
        if (null == token || token.isEmpty()) {
            //没有token
            return authErro(resp, "no login!");
        } else {
            //有token
            try {
                if (url.startsWith("/voice")) {
                    if (token.startsWith("Bearer")) {
                        token = token.substring(7);
                    }
                    String uid = voiceRoomJwtUtil.getUid(token);
                    ServerHttpRequest request = null;
                    if (StringUtils.isNotBlank(uid)) {
                        request = exchange.getRequest().mutate().build();
                        HttpHeaders headers = new HttpHeaders();
                        headers.putAll(exchange.getRequest().getHeaders());
                        headers.set("uid", uid);
                        request = new ServerHttpRequestDecorator(request) {
                            @Override public HttpHeaders getHeaders() {
                                return headers;
                            }
                        };
                    }
                    String tokenKey = "user_token:" + uid;
                    if (!Boolean.TRUE.equals(redisTemplate.hasKey(tokenKey))) {
                        return authErro(resp, "auth error!");
                    } else {
                        String redisToken = redisTemplate.opsForValue().get(tokenKey);
                        if (!token.equals(redisToken)) {
                            return authErro(resp, "auth error!");
                        }
                        //  更新token过期时间
                        redisTemplate.opsForValue()
                                .set(tokenKey, token, Integer.parseInt(exTime), TimeUnit.DAYS);
                    }
                    return request == null ?
                            chain.filter(exchange) :
                            chain.filter(exchange.mutate().request(request).build());
                } else {
                    String userNo = jwtUtil.getUser(token);
                    if(!redisTemplate.hasKey("user_token:"+userNo)){
                        return authErro(resp, "认证过期");
                    }else{
                        String redisToken = String.valueOf(redisTemplate.opsForValue().get("user_token:"+userNo));
                        if(!token.equals(redisToken)){
                            return authErro(resp, "认证过期");
                        }
                        //  更新token过期时间
                        redisTemplate.opsForValue().set("user_token:"+userNo,token,Integer.parseInt(exTime), TimeUnit.DAYS);
                    }
                    return chain.filter(exchange);
                }
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                return authErro(resp, "认证失败");
            }
        }
    }
 
    /**
     * 认证错误输出
     *
     * @param resp 响应对象
     * @param mess 错误信息
     * @return
     */
    private Mono<Void> authErro(ServerHttpResponse resp, String mess) {
        resp.setStatusCode(HttpStatus.UNAUTHORIZED);
        resp.getHeaders().add("Content-Type", "application/json;charset=UTF-8");

        String returnStr = JSON.toJSONString(BaseResult.error(401,mess));
//        try {
//            returnStr = objectMapper.writeValueAsString(returnData);
//        } catch (JsonProcessingException e) {
//            log.error(e.getMessage(), e);
//        }
        DataBuffer buffer = resp.bufferFactory().wrap(returnStr.getBytes(StandardCharsets.UTF_8));
        return resp.writeWith(Flux.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
