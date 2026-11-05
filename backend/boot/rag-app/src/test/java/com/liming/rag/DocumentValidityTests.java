package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 资料有效期（#124、D-046）：已失效和未生效的文档在检索、问答、追问、重新回答里都不被检索和引用，失效日期当天仍可检索；
 * 管理端按有效期筛选、提醒；上传新版本继承失效日期；现有文档（有效期为空）行为不变。
 * 日期按部署的统计时区（测试用默认的 Asia/Shanghai）解释；时区换算的边界见 rag-knowledge 的 DocumentCalendarTest。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rag.knowledge.storage.root=${java.io.tmpdir}/rag-validity-test-files")
@AutoConfigureTestRestTemplate
@Testcontainers
class DocumentValidityTests {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> HITS =
            new ParameterizedTypeReference<>() {
            };
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Shanghai"));

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void signIn() {
        TestLogin.asAdmin(http);
    }

    @Test
    @DisplayName("检索只返回当天有效的文档：已失效、未生效的不出现，失效日期当天的仍出现")
    void searchSkipsExpiredAndPending() {
        long kb = createKnowledgeBase("有效期检索库");
        upload(kb, "临期处理旧版.txt", "临期商品处理规定：临期商品一律打七折出售。", null, TODAY.minusDays(1).toString());
        upload(kb, "临期处理新版.txt", "临期商品处理规定：临期商品一律打五折出售。", null, TODAY.toString());
        upload(kb, "积分新规.txt", "会员积分规定：会员积分每满一百分抵扣一元。", TODAY.plusDays(1).toString(), null);

        var hits = search(kb, "临期商品处理规定打几折");
        assertThat(hits).extracting(hit -> hit.get("documentName")).contains("临期处理新版.txt")
                .doesNotContain("临期处理旧版.txt", "积分新规.txt");
        assertThat(search(kb, "会员积分抵扣")).extracting(hit -> hit.get("documentName"))
                .doesNotContain("积分新规.txt");
    }

    @Test
    @DisplayName("问答、按会话提问、重新回答都不引用已失效的文档")
    @SuppressWarnings("unchecked")
    void answeringNeverCitesExpired() {
        long kb = createKnowledgeBase("有效期问答库");
        upload(kb, "交班旧规.txt", "收银员交班时要清点备用金并交给值班经理保管。", null, TODAY.minusDays(3).toString());

        var asked = TestAsk.ask(http, null, List.of(kb), "收银员交班时要做什么");
        assertThat(asked).containsEntry("refused", true);
        assertThat((List<?>) asked.get("citations")).isEmpty();

        long conversation = ((Number) http.postForEntity("/api/conversations",
                Map.of("knowledgeBaseIds", List.of(kb)), Map.class).getBody().get("id")).longValue();
        var events = SseEvents.post(http, "/api/conversations/" + conversation + "/turns/stream",
                Map.of("question", "收银员交班时要做什么"), TestLogin.token(http, TestLogin.ADMIN_USERNAME,
                        TestLogin.ADMIN_PASSWORD));
        assertThat(events.getLast().name()).isEqualTo("done");
        assertThat(events.getLast().data()).contains("\"refused\":true").doesNotContain("交班旧规.txt");

        upload(kb, "交班新规.txt", "收银员交班时要清点备用金，在交班表上签字后再离开收银台。", null, null);
        var recheck = http.postForEntity("/api/answers/{id}/recheck", null, Map.class,
                ((Number) asked.get("id")).longValue()).getBody();
        assertThat(recheck).containsEntry("refused", false);
        assertThat((List<Map<String, Object>>) recheck.get("citations"))
                .extracting(citation -> citation.get("documentName")).containsOnly("交班新规.txt");
    }

    @Test
    @DisplayName("改有效期立即生效；失效日期早于生效日期、日期格式不对时 400 且不改")
    void changesValidity() {
        long kb = createKnowledgeBase("有效期修改库");
        long id = upload(kb, "冷柜除霜.txt", "冷柜每周一闭店后除霜，除霜时把商品移到备用冷柜。", null, null);
        assertThat(search(kb, "冷柜除霜")).isNotEmpty();

        var expired = change(kb, id, Map.of("effectiveFrom", "", "expiresOn", TODAY.minusDays(1).toString()));
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(expired.getBody()).containsEntry("validity", "EXPIRED")
                .containsEntry("expiresOn", TODAY.minusDays(1).toString());
        assertThat(search(kb, "冷柜除霜")).isEmpty();

        var reversed = new HashMap<String, Object>();
        reversed.put("effectiveFrom", TODAY.toString());
        reversed.put("expiresOn", TODAY.minusDays(5).toString());
        assertThat(change(kb, id, reversed).getBody()).containsEntry("code", "KNOWLEDGE_INVALID");
        assertThat(change(kb, id, Map.of("expiresOn", "2026/12/01")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT expires_on FROM document WHERE id = ?", LocalDate.class, id))
                .isEqualTo(TODAY.minusDays(1));

        var restored = new HashMap<String, Object>();
        restored.put("effectiveFrom", null);
        restored.put("expiresOn", null);
        assertThat(change(kb, id, restored).getBody()).containsEntry("validity", "ACTIVE");
        assertThat(search(kb, "冷柜除霜")).isNotEmpty();
    }

    @Test
    @DisplayName("上传新版本不传失效日期时继承旧版本的；传了就用新的")
    void newVersionInheritsExpiry() {
        long kb = createKnowledgeBase("有效期新版本库");
        var expiry = TODAY.plusDays(60).toString();
        long old = upload(kb, "促销规则.txt", "本季度促销规则：满一百减十。", null, expiry);

        var inherited = replace(kb, old, "促销规则二版.txt", "本季度促销规则：满一百减十五。", null);
        assertThat(inherited).containsEntry("expiresOn", expiry).containsEntry("effectiveFrom", null);
        var changed = replace(kb, ((Number) inherited.get("id")).longValue(), "促销规则三版.txt",
                "本季度促销规则：满一百减二十。", "");
        assertThat(changed).containsEntry("expiresOn", null).containsEntry("validity", "ACTIVE");
    }

    @Test
    @DisplayName("文档按有效期筛选；知识库列表带即将到期、已失效数并能筛选；到期提醒只算自己能管理的库")
    @SuppressWarnings("unchecked")
    void listsAndSummarizes() {
        long kb = createKnowledgeBase("有效期列表库");
        upload(kb, "已失效.txt", "已经作废的旧制度内容一。", null, TODAY.minusDays(1).toString());
        upload(kb, "今天到期.txt", "今天到期的活动规则内容二。", null, TODAY.toString());
        upload(kb, "月内到期.txt", "本月结束的活动规则内容三。", null, TODAY.plusDays(30).toString());
        upload(kb, "未生效.txt", "下周起执行的新制度内容四。", TODAY.plusDays(7).toString(), null);
        upload(kb, "下月后到期.txt", "下月以后到期的制度内容五。", null, TODAY.plusDays(31).toString());

        assertThat(names(kb, "EXPIRED")).containsExactly("已失效.txt");
        assertThat(names(kb, "EXPIRING")).containsExactlyInAnyOrder("今天到期.txt", "月内到期.txt");
        assertThat(names(kb, "PENDING")).containsExactly("未生效.txt");
        assertThat(names(kb, "ACTIVE")).containsExactly("下月后到期.txt");
        assertThat(http.getForEntity("/api/knowledge-bases/{id}/documents?validity=SOON", String.class, kb)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var page = http.getForObject("/api/knowledge-bases/page?keyword=有效期列表库&validity=EXPIRED", Map.class);
        assertThat(page).containsEntry("total", 1);
        assertThat(((List<Map<String, Object>>) page.get("items")).getFirst())
                .containsEntry("expiringCount", 2).containsEntry("expiredCount", 1);
        assertThat(http.getForObject("/api/knowledge-bases/page?keyword=有效期列表库&validity=EXPIRING", Map.class))
                .containsEntry("total", 1);

        var summary = http.getForObject("/api/knowledge-bases/expiry-summary", Map.class);
        assertThat(((Number) summary.get("expiring")).intValue()).isGreaterThanOrEqualTo(2);
        assertThat(summary).containsEntry("warningDays", 30);

        // 没有被授权这个库的管理员看不到它的到期情况。
        String root = TestLogin.token(http, TestLogin.ADMIN_USERNAME, TestLogin.ADMIN_PASSWORD);
        var created = exchange(root, HttpMethod.POST, "/api/users", Map.of("username", "validity01",
                "displayName", "管理员甲", "password", "member-password",
                "roleIds", List.of(TestLogin.builtInRoleId(http, "ADMIN"))));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String other = TestLogin.token(http, "validity01", "member-password");
        assertThat(exchange(other, HttpMethod.GET, "/api/knowledge-bases/expiry-summary", null).getBody())
                .containsEntry("expiring", 0).containsEntry("expired", 0);
    }

    @Test
    @DisplayName("现有文档迁移后有效期为空，长期有效")
    void existingDocumentsStayActive() {
        long kb = createKnowledgeBase("有效期迁移库");
        long id = upload(kb, "老文档.txt", "迁移前就有的门店制度。", null, null);

        assertThat(jdbc.queryForMap("SELECT effective_from, expires_on FROM document WHERE id = ?", id))
                .containsEntry("effective_from", null).containsEntry("expires_on", null);
        assertThat(names(kb, "ACTIVE")).contains("老文档.txt");
    }

    @SuppressWarnings("unchecked")
    private List<Object> names(long kb, String validity) {
        var page = http.getForObject("/api/knowledge-bases/{id}/documents?validity={v}", Map.class, kb, validity);
        return ((List<Map<String, Object>>) page.get("items")).stream().map(item -> item.get("filename")).toList();
    }

    private List<Map<String, Object>> search(long kb, String question) {
        var response = http.exchange("/api/knowledge-bases/{id}/search?q={q}", HttpMethod.GET, null, HITS, kb,
                question);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private org.springframework.http.ResponseEntity<Map<String, Object>> change(long kb, long id,
            Map<String, Object> body) {
        return (org.springframework.http.ResponseEntity) http.exchange(
                "/api/knowledge-bases/{kb}/documents/{id}/validity", HttpMethod.PUT, new HttpEntity<>(body), Map.class,
                kb, id);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private org.springframework.http.ResponseEntity<Map<String, Object>> exchange(String token, HttpMethod method,
            String path, Object body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return (org.springframework.http.ResponseEntity) http.exchange(path, method, new HttpEntity<>(body, headers),
                Map.class);
    }

    private long createKnowledgeBase(String name) {
        var created = http.postForEntity("/api/knowledge-bases", Map.of("name", name), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) created.getBody().get("id")).longValue();
    }

    /** 上传并等处理完，返回文档 id；日期为 null 时不传这个字段。 */
    private long upload(long kb, String filename, String text, String effectiveFrom, String expiresOn) {
        var response = post("/api/knowledge-bases/{id}/documents", form(filename, text, effectiveFrom, expiresOn), kb);
        assertThat(TestDocuments.awaitProcessed(http, kb, response, null)).containsEntry("status", "READY");
        return ((Number) response.get("id")).longValue();
    }

    private Map<String, Object> replace(long kb, long old, String filename, String text, String expiresOn) {
        var response = post("/api/knowledge-bases/{kb}/documents/{id}/versions", form(filename, text, null, expiresOn),
                kb, old);
        return TestDocuments.awaitProcessed(http, kb, response, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, LinkedMultiValueMap<String, Object> body, Object... variables) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var response = http.postForEntity(path, new HttpEntity<>(body, headers), Map.class, variables);
        assertThat(response.getStatusCode()).as("上传 %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private static LinkedMultiValueMap<String, Object> form(String filename, String text, String effectiveFrom,
            String expiresOn) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        if (effectiveFrom != null) {
            body.add("effectiveFrom", effectiveFrom);
        }
        if (expiresOn != null) {
            body.add("expiresOn", expiresOn);
        }
        return body;
    }
}
