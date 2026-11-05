package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.cache.CacheSpec;
import com.liming.rag.cache.Caches;
import com.liming.rag.identity.api.IdentityPermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 缓存一致性（D-053），对着真实的 Redis：清除之后，加载期间读到的旧值不能写回；并发请求下收回权限后，之后发出的请求
 * 全部按新权限判定。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class CacheConsistencyTests {

    record Probe(String value) {
    }

    private static final CacheSpec<Probe> PROBE = new CacheSpec<>("consistency-probe", Probe.class);
    private static final String PASSWORD = "member-password";
    private static final int ROUNDS = 15;
    private static final int READERS = 6;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private Caches caches;

    @Autowired
    private RedissonClient redis;

    @Test
    @DisplayName("加载期间被清除时，读到的旧值不写回 Redis（Lua 比较代数）；之后读到新值")
    void staleLoadIsNotWrittenBack() {
        var cache = caches.cache(PROBE);
        var stale = cache.get("k", () -> {
            cache.evict("k");
            return new Probe("old");
        });

        assertThat(stale).isEqualTo(new Probe("old"));
        assertThat(redis.getBucket("rag:cache:consistency-probe:k").isExists()).isFalse();
        assertThat(cache.get("k", () -> new Probe("new"))).isEqualTo(new Probe("new"));
        assertThat(cache.get("k", () -> new Probe("other"))).as("新值已写入").isEqualTo(new Probe("new"));
    }

    @Test
    @DisplayName("并发且加载较慢时：开始读的时刻在某次清除之后，就读不到那次改动之前的值（真实 Redis，多轮）")
    void concurrentSlowLoadsNeverResurrectOldValues() throws Exception {
        var cache = caches.cache(new CacheSpec<>("consistency-race", Probe.class));
        var source = new java.util.concurrent.atomic.AtomicInteger();
        var committed = new java.util.concurrent.atomic.AtomicInteger();
        var stop = new AtomicBoolean();
        var violations = Collections.synchronizedList(new ArrayList<String>());
        var pool = Executors.newFixedThreadPool(READERS);
        for (int i = 0; i < READERS; i++) {
            pool.execute(() -> {
                while (!stop.get()) {
                    int floor = committed.get();
                    var probe = cache.get("k", () -> {
                        int read = source.get();
                        sleep(5);
                        return new Probe(String.valueOf(read));
                    });
                    int seen = Integer.parseInt(probe.value());
                    if (seen < floor) {
                        violations.add("清除第 " + floor + " 版之后开始读，却读到第 " + seen + " 版");
                    }
                }
            });
        }
        for (int version = 1; version <= 60; version++) {
            source.set(version);
            cache.evict("k");
            committed.set(version);
            sleep(15);
        }
        stop.set(true);
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(violations).isEmpty();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("并发：缓存不停地清除重载时收回权限，收回请求返回之后发出的请求全部 403（多轮）")
    void revocationWinsUnderConcurrency() throws Exception {
        String root = TestLogin.token(http, TestLogin.ADMIN_USERNAME, TestLogin.ADMIN_PASSWORD);
        long role = id(call(root, HttpMethod.POST, "/api/roles",
                Map.of("name", "并发角色", "permissions", List.of(IdentityPermissions.USER_VIEW))));
        long user = id(call(root, HttpMethod.POST, "/api/users", Map.of("username", "race01", "displayName", "并发",
                "password", PASSWORD, "roleIds", List.of(role))));
        String token = (String) call(null, HttpMethod.POST, "/api/auth/login",
                Map.of("username", "race01", "password", PASSWORD)).getBody().get("token");

        var violations = Collections.synchronizedList(new ArrayList<String>());
        for (int round = 1; round <= ROUNDS; round++) {
            call(root, HttpMethod.PATCH, "/api/roles/" + role, Map.of("permissions", List.of(IdentityPermissions.USER_VIEW)));
            assertThat(status(token)).isEqualTo(HttpStatus.OK);

            var stop = new AtomicBoolean();
            var revokedAt = new AtomicLong(Long.MAX_VALUE);
            var pool = Executors.newFixedThreadPool(READERS + 1);
            // 不停地给账号重新分配同一个角色：每次都清除它的缓存，读请求一直在从库里加载。
            pool.execute(() -> {
                while (!stop.get()) {
                    call(root, HttpMethod.PUT, "/api/users/" + user + "/roles", Map.of("roleIds", List.of(role)));
                }
            });
            int currentRound = round;
            for (int i = 0; i < READERS; i++) {
                pool.execute(() -> {
                    while (!stop.get()) {
                        long started = System.nanoTime();
                        var status = status(token);
                        if (started > revokedAt.get() && status != HttpStatus.FORBIDDEN) {
                            violations.add("第 " + currentRound + " 轮：收回后发出的请求返回 " + status);
                        }
                    }
                });
            }
            Thread.sleep(150);
            call(root, HttpMethod.PATCH, "/api/roles/" + role, Map.of("permissions", List.of()));
            revokedAt.set(System.nanoTime());
            Thread.sleep(400);
            stop.set(true);
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            assertThat(status(token)).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(violations).isEmpty();
    }

    private HttpStatus status(String token) {
        return HttpStatus.valueOf(call(token, HttpMethod.GET, "/api/users", null).getStatusCode().value());
    }

    private static long id(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).as(String.valueOf(response.getBody())).isTrue();
        return ((Number) response.getBody().get("id")).longValue();
    }

    private ResponseEntity<Map<String, Object>> call(String token, HttpMethod method, String path, Object body) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<Map<String, Object>>() { });
    }
}
