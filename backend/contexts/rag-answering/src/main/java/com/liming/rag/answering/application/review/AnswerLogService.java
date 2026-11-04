package com.liming.rag.answering.application.review;

import com.liming.rag.answering.application.AnswerRecorded;
import com.liming.rag.answering.application.AnsweringException;
import com.liming.rag.answering.application.Paged;
import com.liming.rag.answering.application.ask.AskResult;
import com.liming.rag.answering.domain.AnswerRecord;
import com.liming.rag.answering.domain.AnswerRecordRepository;
import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.knowledge.api.KnowledgeAccess;
import com.liming.rag.settings.api.StatsZone;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回答记录与反馈。每次问答（包括拒答）都记下来并给回答一个 id；提问人可以对自己的回答点赞点踩、留言。
 */
@Service
public class AnswerLogService {

    private static final Logger log = LoggerFactory.getLogger(AnswerLogService.class);

    /** 回答记录最多按多少天筛选。 */
    static final int MAX_STATS_DAYS = 90;

    private final AnswerRecordRepository records;
    private final AnswerReviews reviews;
    private final UserDirectory users;
    private final KnowledgeAccess access;
    private final StatsZone zones;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AnswerLogService(AnswerRecordRepository records, AnswerReviews reviews, UserDirectory users,
            KnowledgeAccess access, StatsZone zones, ApplicationEventPublisher events, Clock clock) {
        this.records = records;
        this.reviews = reviews;
        this.users = users;
        this.access = access;
        this.zones = zones;
        this.events = events;
        this.clock = clock;
    }

    /**
     * 管理员按筛选条件分页查看回答记录，按时间从新到旧（D-039），带上提问人的显示名。
     *
     * @param filter disliked（被点踩）、refused（答不上来）或 all，不区分大小写
     * @param status open（待处理）、resolved（已处理）或 all
     * @param days 最近几天（含今天），为 null 不限
     * @param keyword 匹配问题，为空不筛
     * @throws AnsweringException 筛选条件不认识
     */
    @Transactional(readOnly = true)
    public Paged<AnswerReview> review(TenantId tenantId, String filter, String status, Integer days, String keyword,
            int page, int size) {
        var parsedFilter = parse(AnswerReview.Filter.class, filter, "查看范围只能是 disliked、refused 或 all");
        var parsedStatus = parse(AnswerReview.Status.class, status, "处理状态只能是 open、resolved 或 all");
        if (days != null && (days < 1 || days > MAX_STATS_DAYS)) {
            throw invalid("天数只能是 1 到 " + MAX_STATS_DAYS + " 天");
        }
        var zone = zones.statsZone();
        var since = days == null ? null
                : LocalDate.now(clock.withZone(zone)).minusDays(days - 1L).atStartOfDay(zone).toOffsetDateTime();
        var trimmed = keyword == null || keyword.isBlank() ? null : keyword.strip();
        var found = reviews.page(tenantId, new AnswerReview.Query(parsedFilter, parsedStatus, since, trimmed,
                new Paged.Request(page, size)));
        var names = users.summaries(tenantId, found.items().stream().map(AnswerReview::userId).distinct().toList());
        return found.map(review -> {
            var asker = names.get(review.userId());
            return review.withAskerName(asker == null ? null : asker.displayName());
        });
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String message) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid(message);
        }
    }

    /**
     * 管理员把记录标记为已处理（附处理说明）或重新打开。
     *
     * @param status RESOLVED 或 OPEN
     * @throws AnsweringException 记录不存在
     * @throws AnsweringException 状态不认识，或处理说明太长
     */
    @Transactional
    public void markReview(TenantId tenantId, UserId by, long answerId, String status, String note) {
        AnswerRecord.ReviewStatus parsed;
        try {
            parsed = AnswerRecord.ReviewStatus.valueOf(status == null ? "" : status);
        } catch (IllegalArgumentException e) {
            throw invalid("处理状态只能是 RESOLVED 或 OPEN");
        }
        var record = find(tenantId, answerId);
        try {
            record.review(by, parsed, note);
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
        records.saveReview(record);
    }

    /** 记录里员工原来的问题，供管理员“重新回答”。 */
    @Transactional(readOnly = true)
    public String questionOf(TenantId tenantId, long answerId) {
        return find(tenantId, answerId).question();
    }

    /** 记下一次问答，返回带 id 的回答；没点名知识库，检索的是提问人能访问的全部知识库。 */
    @Transactional
    public AskResult record(TenantId tenantId, UserId asker, String question, AskResult answer) {
        return record(tenantId, asker, question, answer, List.of());
    }

    /**
     * 记下一次问答，连同实际检索的知识库：点名的库里提问人能访问的那些，没点名时是能访问的全部（D-047）。
     * 检索时的权限过滤仍在 rag-knowledge 的检索服务里，这里只为了让知识缺口显示涉及的知识库。
     *
     * @param requested 员工点名的知识库，可以为空
     */
    @Transactional
    public AskResult record(TenantId tenantId, UserId asker, String question, AskResult answer, List<Long> requested) {
        return recordSearched(tenantId, asker, question, answer, access.scope(tenantId, asker, requested));
    }

    /**
     * 记下一次问答，{@code searched} 是已经按权限算好的实际检索范围（会话提问时由会话给出，为空表示所选的库都已收回授权）。
     */
    @Transactional
    public AskResult recordSearched(TenantId tenantId, UserId asker, String question, AskResult answer,
            List<Long> searched) {
        var saved = records.add(AnswerRecord.record(tenantId, asker, question, answer.reply(), List.copyOf(searched)));
        events.publishEvent(new AnswerRecorded(tenantId, saved.id(), saved.question()));
        logOutcome(saved.id(), asker, answer);
        return answer.withId(saved.id());
    }

    /** 拒答和低分改写各留一行日志（#145 C1），不记问题原文。 */
    private static void logOutcome(long id, UserId asker, AskResult answer) {
        var rewrite = answer.rewrite() == null ? null : answer.rewrite().outcome();
        if (rewrite != null) {
            log.info("event=query_rewritten answer={} outcome={}", id, rewrite);
        }
        if (answer.refused()) {
            log.info("event=answer_refused answer={} user={} reason={} top={}", id, asker.value(),
                    answer.refusalReason(), answer.topRelevance());
        }
    }

    /**
     * @throws AnsweringException 回答不存在，或不是这位用户问的
     * @throws AnsweringException 评分不是 UP / DOWN，或留言太长
     */
    @Transactional
    public void rate(TenantId tenantId, UserId by, long answerId, String rating, String comment) {
        AnswerRecord.Rating parsed;
        try {
            parsed = AnswerRecord.Rating.valueOf(rating == null ? "" : rating);
        } catch (IllegalArgumentException e) {
            throw invalid("评分只能是 UP 或 DOWN");
        }
        var record = find(tenantId, answerId);
        try {
            record.rate(by, parsed, comment);
        } catch (AnswerRecord.NotAskerException e) {
            throw notFound();
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
        records.saveFeedback(record);
    }

    /** 撤销评价。 */
    @Transactional
    public void clearRating(TenantId tenantId, UserId by, long answerId) {
        var record = find(tenantId, answerId);
        try {
            record.clearRating(by);
        } catch (AnswerRecord.NotAskerException e) {
            throw notFound();
        }
        records.saveFeedback(record);
    }

    private AnswerRecord find(TenantId tenantId, long answerId) {
        return records.find(tenantId, answerId).orElseThrow(AnswerLogService::notFound);
    }

    private static AnsweringException notFound() {
        return new AnsweringException(AnsweringException.Reason.NOT_FOUND, "回答不存在");
    }

    private static AnsweringException invalid(String message) {
        return new AnsweringException(AnsweringException.Reason.INVALID, message);
    }
}
