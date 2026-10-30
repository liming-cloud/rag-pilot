package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.answering.application.ConversationAskService;
import com.liming.rag.answering.application.ConversationRetention;
import com.liming.rag.answering.domain.ConversationTurn;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 员工端会话（#107、D-036）：只能操作自己的会话；知识库按会话选、只能选已授权的库；按会话提问、停止、重答；
 * 删除只影响员工端，回答记录保留。模型用本地确定性实现。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rag.knowledge.storage.root=${java.io.tmpdir}/rag-conversation-test-files")
@AutoConfigureTestRestTemplate
@Testcontainers
class ConversationTests {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {
    };

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private TestRestTemplate http;

    private String admin;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConversationRetention retention;

    @Autowired
    private ConversationAskService asking;

    @BeforeEach
    void signIn() {
        admin = TestLogin.token(http, TestLogin.ADMIN_USERNAME, TestLogin.ADMIN_PASSWORD);
    }

    @Test
    @DisplayName("新会话默认选中全部已授权的库；点名无权限的库被忽略；列表只有自己的会话")
    void createsConversationWithAccessibleKnowledgeBases() {
        long store = createKnowledgeBase("会话测试门店库");
        long manager = createKnowledgeBase("会话测试店长库");
        long staffId = createStaff("conv01");
        grant(store, staffId);
        String staff = TestLogin.token(http, "conv01", "staff-password");

        var byDefault = exchange(HttpMethod.POST, "/api/conversations", Map.of(), staff);
        assertThat(byDefault.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ids(byDefault.getBody().get("knowledgeBaseIds"))).containsExactly(store);
        assertThat(byDefault.getBody()).containsEntry("title", "新对话");

        var probing = exchange(HttpMethod.POST, "/api/conversations", Map.of("knowledgeBaseIds", List.of(store, manager)), staff);
        assertThat(ids(probing.getBody().get("knowledgeBaseIds"))).containsExactly(store);

        assertThat(list(staff)).hasSize(2);
        assertThat(list(admin)).extracting(conversation -> conversation.get("id"))
                .doesNotContain(byDefault.getBody().get("id"), probing.getBody().get("id"));
    }

    @Test
    @DisplayName("别人的会话看不到、改不了、删不掉，一律返回 404")
    void othersConversationsAreNotFound() {
        long store = createKnowledgeBase("会话测试越权库");
        long ownerId = createStaff("conv02");
        long otherId = createStaff("conv03");
        grant(store, ownerId);
        grant(store, otherId);
        String owner = TestLogin.token(http, "conv02", "staff-password");
        String other = TestLogin.token(http, "conv03", "staff-password");
        long id = id(exchange(HttpMethod.POST, "/api/conversations", Map.of(), owner));
        String path = "/api/conversations/" + id;

        assertThat(exchange(HttpMethod.GET, path, null, other).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(HttpMethod.PATCH, path, Map.of("title", "偷改"), other).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(HttpMethod.DELETE, path, null, other).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(HttpMethod.GET, path, null, owner).getBody()).containsEntry("title", "新对话");
    }

    @Test
    @DisplayName("重命名、改选知识库（只能选已授权的库）、删除")
    void renamesSelectsAndDeletes() {
        long store = createKnowledgeBase("会话测试改选库一");
        long goods = createKnowledgeBase("会话测试改选库二");
        long manager = createKnowledgeBase("会话测试改选库三");
        long staffId = createStaff("conv04");
        grant(store, staffId);
        grant(goods, staffId);
        String staff = TestLogin.token(http, "conv04", "staff-password");
        long id = id(exchange(HttpMethod.POST, "/api/conversations", Map.of(), staff));
        String path = "/api/conversations/" + id;

        var renamed = exchange(HttpMethod.PATCH, path, Map.of("title", "  冷柜温度  "), staff);
        assertThat(renamed.getBody()).containsEntry("title", "冷柜温度");
        assertThat(exchange(HttpMethod.PATCH, path, Map.of("title", "长".repeat(41)), staff).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        var narrowed = exchange(HttpMethod.PATCH, path, Map.of("knowledgeBaseIds", List.of(goods, manager)), staff);
        assertThat(ids(narrowed.getBody().get("knowledgeBaseIds"))).containsExactly(goods);
        assertThat(exchange(HttpMethod.PATCH, path, Map.of("knowledgeBaseIds", List.of(manager)), staff).getStatusCode())
                .as("全是无权限的库时不能保存，否则会话没有可检索的库").isEqualTo(HttpStatus.BAD_REQUEST);

        var detail = exchange(HttpMethod.GET, path, null, staff).getBody();
        assertThat(detail.get("knowledgeBases").toString()).contains("会话测试改选库二").doesNotContain("会话测试改选库三");

        assertThat(exchange(HttpMethod.DELETE, path, null, staff).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange(HttpMethod.GET, path, null, staff).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("按会话提问：事件依次是 turn、status、delta、done；详情里有这一轮，标题变成问题；回答记录也有")
    void asksInConversation() {
        var setup = staffWithStore("conv05", "会话测试提问库");
        long id = id(exchange(HttpMethod.POST, "/api/conversations", Map.of(), setup.token()));

        var events = SseEvents.post(http, "/api/conversations/" + id + "/turns/stream",
                Map.of("question", "青柚气泡水在哪个货架？"), setup.token());

        assertThat(events).extracting(SseEvents.Event::name).startsWith("turn").contains("status").endsWith("done");
        var done = json(events.getLast().data());
        assertThat(done).containsEntry("question", "青柚气泡水在哪个货架？").containsEntry("status", "ANSWERED");
        assertThat(((Map<?, ?>) done.get("answer")).get("refused")).isEqualTo(false);
        var detail = exchange(HttpMethod.GET, "/api/conversations/" + id, null, setup.token()).getBody();
        assertThat((List<?>) detail.get("turns")).hasSize(1);
        assertThat(detail.get("title")).isEqualTo("青柚气泡水在哪个货架？");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_record WHERE user_id = ?", Integer.class,
                setup.userId())).isEqualTo(1);
    }

    @Test
    @DisplayName("重新生成和修改问题只能针对最后一轮；替换后主线上仍只有一轮，旧的一轮留着供审计")
    @SuppressWarnings("unchecked")
    void replacesOnlyTheLastTurn() {
        var setup = staffWithStore("conv06", "会话测试重答库");
        long id = id(exchange(HttpMethod.POST, "/api/conversations", Map.of(), setup.token()));
        String path = "/api/conversations/" + id + "/turns/stream";
        long first = turnId(SseEvents.post(http, path, Map.of("question", "青柚在哪？"), setup.token()));
        long second = turnId(SseEvents.post(http, path, Map.of("question", "补货要先做什么？"), setup.token()));

        var stale = streamStatus(path, Map.of("question", "青柚在哪？", "replacesTurnId", first), setup.token());
        assertThat(stale).as("开始生成前就校验，不进事件流").isEqualTo(HttpStatus.BAD_REQUEST);

        SseEvents.post(http, path, Map.of("question", "补货前要移出什么？", "replacesTurnId", second), setup.token());
        var turns = (List<Map<String, Object>>) (List<?>) exchange(HttpMethod.GET, "/api/conversations/" + id, null,
                setup.token()).getBody().get("turns");
        assertThat(turns).extracting(turn -> turn.get("question")).containsExactly("青柚在哪？", "补货前要移出什么？");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversation_turn WHERE conversation_id = ? AND superseded",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("授权收回后：知识库标为无权限，历史引用不再给原文，之后的提问不检索它；全部收回时直接拒答")
    void respectsRevokedAccess() {
        var setup = staffWithStore("conv07", "会话测试收回库");
        long id = id(exchange(HttpMethod.POST, "/api/conversations", Map.of(), setup.token()));
        String path = "/api/conversations/" + id;
        SseEvents.post(http, path + "/turns/stream", Map.of("question", "青柚气泡水在哪个货架？"), setup.token());
        var before = exchange(HttpMethod.GET, path, null, setup.token()).getBody().toString();
        assertThat(before).contains("A 区 03 号货架");

        revoke(setup.knowledgeBaseId(), setup.userId());

        var after = exchange(HttpMethod.GET, path, null, setup.token()).getBody();
        assertThat(after.get("knowledgeBases").toString()).contains("accessible=false");
        assertThat(after.toString()).as("历史回答的文字还在，引用原文不再返回").contains("excerpt=null")
                .contains("青柚气泡水在哪个货架？");
        var events = SseEvents.post(http, path + "/turns/stream", Map.of("question", "青柚气泡水在哪个货架？"),
                setup.token());
        var done = json(events.getLast().data());
        assertThat(done.get("answer").toString()).contains("refused=true").contains("NO_EVIDENCE")
                .doesNotContain("A 区 03 号货架");
    }

    @Test
    @DisplayName("别人不能在我的会话里提问、也不能停止；停止已结束的一轮没有副作用")
    void othersCannotAskOrStop() {
        var setup = staffWithStore("conv08", "会话测试停止库");
        long otherId = createStaff("conv09");
        grant(setup.knowledgeBaseId(), otherId);
        String other = TestLogin.token(http, "conv09", "staff-password");
        long id = id(exchange(HttpMethod.POST, "/api/conversations", Map.of(), setup.token()));
        long turn = turnId(SseEvents.post(http, "/api/conversations/" + id + "/turns/stream",
                Map.of("question", "青柚在哪？"), setup.token()));

        assertThat(streamStatus("/api/conversations/" + id + "/turns/stream", Map.of("question", "青柚在哪？"), other))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(HttpMethod.POST, "/api/conversations/" + id + "/turns/" + turn + "/stop", null, other)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(HttpMethod.POST, "/api/conversations/" + id + "/turns/" + turn + "/stop", null,
                setup.token()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("停止生成：生成前就停了的一轮存为 STOPPED、没有回答，也不写回答记录")
    void stopsTurn() {
        var setup = staffWithStore("conv12", "会话测试中止库");
        long id = id(exchange(HttpMethod.POST, "/api/conversations", Map.of(), setup.token()));
        var tenant = new TenantId(1);
        var user = new UserId(setup.userId());

        var pending = asking.start(tenant, user, id, "青柚气泡水在哪个货架？", null);
        asking.stop(tenant, user, id, pending.turnId());
        var turn = asking.run(pending, new ConversationAskService.Events(status -> {
        }, delta -> {
        }));

        assertThat(turn.status()).isEqualTo(ConversationTurn.Status.STOPPED);
        assertThat(turn.answer()).isNull();
        assertThat(turns(id, setup.token())).singleElement()
                .satisfies(saved -> assertThat(saved).containsEntry("status", "STOPPED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_record WHERE user_id = ?", Integer.class,
                setup.userId())).isZero();
    }

    @Test
    @DisplayName("员工断开连接后，服务端仍把这一轮生成完并保存")
    void finishesAfterDisconnect() throws InterruptedException {
        var setup = staffWithStore("conv10", "会话测试断开库");
        long id = id(exchange(HttpMethod.POST, "/api/conversations", Map.of(), setup.token()));

        var first = SseEvents.postAndDisconnect(http, "/api/conversations/" + id + "/turns/stream",
                Map.of("question", "青柚气泡水在哪个货架？"), setup.token());

        assertThat(first.name()).isEqualTo("turn");
        for (int i = 0; i < 50 && turns(id, setup.token()).isEmpty(); i++) {
            Thread.sleep(100);
        }
        assertThat(turns(id, setup.token())).singleElement()
                .satisfies(turn -> assertThat(turn).containsEntry("status", "ANSWERED"));
    }

    @Test
    @DisplayName("会话保留：最后一次提问超过保留期的会话被清理，回答记录保留；删除会话也不删回答记录")
    void purgesExpiredConversationsButKeepsAnswerRecords() {
        var setup = staffWithStore("conv11", "会话测试保留库");
        long expired = id(exchange(HttpMethod.POST, "/api/conversations", Map.of(), setup.token()));
        long active = id(exchange(HttpMethod.POST, "/api/conversations", Map.of(), setup.token()));
        SseEvents.post(http, "/api/conversations/" + expired + "/turns/stream", Map.of("question", "保留期测试问题"),
                setup.token());
        jdbc.update("UPDATE conversation SET last_asked_at = now() - interval '31 days' WHERE id = ?", expired);

        assertThat(retention.purge()).isGreaterThanOrEqualTo(1);

        assertThat(exchange(HttpMethod.GET, "/api/conversations/" + expired, null, setup.token()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(HttpMethod.GET, "/api/conversations/" + active, null, setup.token()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_record WHERE question = '保留期测试问题'",
                Integer.class)).isEqualTo(1);
    }

    /** 一个店员和一个只授权给他的知识库，库里有一篇陈列文档。 */
    private record StaffSetup(String token, long userId, long knowledgeBaseId) {
    }

    private StaffSetup staffWithStore(String username, String knowledgeBaseName) {
        long store = createKnowledgeBase(knowledgeBaseName);
        upload(store, "商品陈列.txt", "青柚气泡水陈列在一层 A 区 03 号货架第二层。补货时先移出临期批次。");
        long userId = createStaff(username);
        grant(store, userId);
        return new StaffSetup(TestLogin.token(http, username, "staff-password"), userId, store);
    }

    private void revoke(long knowledgeBaseId, long userId) {
        var response = http.exchange("/api/knowledge-bases/{id}/members/{userId}", HttpMethod.DELETE,
                new HttpEntity<>(auth(admin)), Void.class, knowledgeBaseId, userId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> turns(long conversationId, String token) {
        return (List<Map<String, Object>>) exchange(HttpMethod.GET, "/api/conversations/" + conversationId, null, token)
                .getBody().get("turns");
    }

    /** 像前端一样带 Accept: text/event-stream 发起提问，只看状态码（校验不通过时不进事件流）。 */
    private HttpStatusCode streamStatus(String path, Object body, String token) {
        var headers = auth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_PROBLEM_JSON));
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class).getStatusCode();
    }

    private static long turnId(List<SseEvents.Event> events) {
        assertThat(events.getFirst().name()).isEqualTo("turn");
        return ((Number) json(events.getFirst().data()).get("id")).longValue();
    }

    private static Map<String, Object> json(String data) {
        return JsonMapper.builder().build().readValue(data, new TypeReference<>() {
        });
    }

    private long createKnowledgeBase(String name) {
        return id(exchange(HttpMethod.POST, "/api/knowledge-bases", Map.of("name", name), admin));
    }

    private long createStaff(String username) {
        return id(exchange(HttpMethod.POST, "/api/users", Map.of("username", username, "displayName", "店员",
                "password", "staff-password", "role", "STAFF"), admin));
    }

    private void grant(long knowledgeBaseId, long userId) {
        var response = http.exchange("/api/knowledge-bases/{id}/members/{userId}", HttpMethod.PUT,
                new HttpEntity<>(auth(admin)), Void.class, knowledgeBaseId, userId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    void upload(long knowledgeBaseId, String filename, String text) {
        var file = new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", file);
        var headers = auth(admin);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var response = http.exchange("/api/knowledge-bases/{id}/documents", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class, knowledgeBaseId);
        assertThat(TestDocuments.awaitProcessed(http, knowledgeBaseId, response.getBody(), admin))
                .containsEntry("status", "READY");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    ResponseEntity<Map<String, Object>> exchange(HttpMethod method, String path, Object body, String token) {
        var headers = auth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return (ResponseEntity) http.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }

    private List<Map<String, Object>> list(String token) {
        return http.exchange("/api/conversations", HttpMethod.GET, new HttpEntity<>(auth(token)), LIST).getBody();
    }

    private static long id(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) response.getBody().get("id")).longValue();
    }

    private static List<Long> ids(Object values) {
        return ((List<?>) values).stream().map(value -> ((Number) value).longValue()).toList();
    }

    private static HttpHeaders auth(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
