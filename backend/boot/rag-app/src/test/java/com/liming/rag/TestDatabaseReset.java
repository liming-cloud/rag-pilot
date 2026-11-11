package com.liming.rag;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.stp.StpInterface;
import com.liming.rag.cache.Caches;
import com.liming.rag.cache.typed.TypedCache;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.testcontainers.containers.GenericContainer;

/**
 * 所有测试类共用一个数据库（{@link TestDatabase}），每个测试类仍从干净的数据开始（#167）：第一个测试开始前给数据库拍快照
 * （迁移和启动初始化之后，含超级管理员）；每个测试类结束后先等后台入库做完，再清空所有表、按快照恢复数据和序列，并清掉这个
 * 上下文的 Redis、一级缓存和测试客户端上的登录。恢复放在结束时做：上下文要等 {@code @BeforeAll}（如启动模拟服务）之后才加载。
 * 每个测试开始前把 Sa-Token 的静态组件指回当前上下文。在 {@code META-INF/spring.factories} 里登记。
 */
final class TestDatabaseReset extends AbstractTestExecutionListener {

    private static final Duration IDLE_WAIT = Duration.ofSeconds(30);
    private static final int RESTORE_ATTEMPTS = 5;

    private static final String SNAPSHOT = """
            do $$
            declare t text;
            begin
              create schema test_snapshot;
              for t in select tablename from pg_tables
                       where schemaname = 'public' and tablename <> 'flyway_schema_history' loop
                execute format('create table test_snapshot.%I as table public.%I', '@' || t, t);
              end loop;
              create table test_snapshot."@@sequences" as
                select sequencename as name, last_value from pg_sequences where schemaname = 'public';
            end $$""";

    /** 快照表名前加 {@code @}，按表名查元数据的测试不会查到两份。外键在恢复期间不检查（session_replication_role），生成列不写。 */
    private static final String RESTORE = """
            do $$
            declare t text; columns text; s record;
            begin
              set local session_replication_role = replica;
              execute (select 'truncate ' || string_agg(format('public.%I', tablename), ', ') || ' restart identity'
                       from pg_tables where schemaname = 'public' and tablename <> 'flyway_schema_history');
              for t in select substr(tablename, 2) from pg_tables
                       where schemaname = 'test_snapshot' and tablename <> '@@sequences' loop
                select string_agg(format('%I', attname), ', ' order by attnum) into columns from pg_attribute
                 where attrelid = format('public.%I', t)::regclass and attnum > 0 and not attisdropped
                   and attgenerated = '';
                execute format('insert into public.%I (%s) overriding system value select %s from test_snapshot.%I',
                               t, columns, columns, '@' || t);
              end loop;
              for s in select name, last_value from test_snapshot."@@sequences" loop
                if s.last_value is null then
                  perform setval(format('public.%I', s.name), 1, false);
                else
                  perform setval(format('public.%I', s.name), s.last_value);
                end if;
              end loop;
            end $$""";

    private static boolean snapshotTaken;

    @Override
    public int getOrder() {
        // after 回调按逆序执行：排在最后，结束时才能先于 @DirtiesContext 关闭上下文恢复数据库。
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void prepareTestInstance(TestContext testContext) {
        var context = testContext.getApplicationContext();
        synchronized (TestDatabaseReset.class) {
            if (!snapshotTaken) {
                jdbc(context).execute(SNAPSHOT);
                snapshotTaken = true;
            }
        }
        // Sa-Token 把会话存储和权限查询放在静态的 SaManager 里，每个上下文启动时覆盖一次；缓存着的上下文再次使用时要指回
        // 自己的，否则用的是最后启动的那个上下文的 Redis 和权限缓存。
        SaManager.setSaTokenDao(context.getBean(SaTokenDao.class));
        SaManager.setStpInterface(context.getBean(StpInterface.class));
    }

    @Override
    public void afterTestClass(TestContext testContext) throws Exception {
        if (!testContext.hasApplicationContext()) {
            return;
        }
        var context = testContext.getApplicationContext();
        awaitIdle(context);
        synchronized (TestDatabaseReset.class) {
            if (snapshotTaken) {
                restore(jdbc(context));
            }
        }
        clearCaches(context);
        context.getBeanProvider(TestRestTemplate.class)
                .ifAvailable(http -> http.getRestTemplate().setInterceptors(List.of()));
    }

    /** 清表要逐张拿排他锁，和缓存着的上下文里的定时任务（如入库重试）可能死锁；恢复是一条语句，死锁时整体回滚，重试即可。 */
    private static void restore(JdbcTemplate jdbc) {
        for (int attempt = 1; ; attempt++) {
            try {
                jdbc.execute(RESTORE);
                return;
            } catch (PessimisticLockingFailureException e) {
                if (attempt == RESTORE_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private static JdbcTemplate jdbc(ApplicationContext context) {
        return new JdbcTemplate(context.getBean(DataSource.class));
    }

    private static void awaitIdle(ApplicationContext context) throws InterruptedException {
        var deadline = System.nanoTime() + IDLE_WAIT.toNanos();
        for (var executor : context.getBeansOfType(ThreadPoolTaskExecutor.class).values()) {
            while ((executor.getActiveCount() > 0 || executor.getQueueSize() > 0) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
        }
    }

    private static void clearCaches(ApplicationContext context) throws IOException, InterruptedException,
            ReflectiveOperationException {
        if (context.containsBean(TestRedis.BEAN)) {
            var redis = context.getBean(TestRedis.BEAN, GenericContainer.class);
            if (redis.isRunning()) {
                redis.execInContainer("redis-cli", "-a", TestRedis.PASSWORD, "--no-auth-warning", "FLUSHALL");
            }
        }
        // 一级缓存在 JsonCaches 里按名字各建一个，接口上没有“全部清掉”，测试里按字段取出来逐个清。
        var caches = context.getBean(Caches.class);
        var typed = caches.getClass().getDeclaredField("typed");
        typed.setAccessible(true);
        for (var cache : ((Map<?, ?>) typed.get(caches)).values()) {
            ((TypedCache<?>) cache).clear();
        }
    }
}
