package io.github.dolow.reconlens.chat;

import io.github.dolow.reconlens.store.ReconRepository;
import io.github.dolow.reconlens.store.RuleRepository;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 系统事实（{@link ContextFacts}）的短命缓存：同一份目录，30 秒内只拼一次。
 *
 * <p>为什么只缓存这个：每句话都要"规则目录 + 最近任务 + 表名"，它拼一次要查两遍库、
 * 扫一遍规则、再拼一串字符串——而这些内容在两次对话之间几乎不变。缓存它省的是每轮
 * 的固定开销，不影响任何"用户说了什么"的语义。
 *
 * <p>失效键为什么是<b>两个计数</b>而不是版本号：
 * <ul>
 *   <li>规则表只追加，行数变 = 有新规则/新版本；用 {@code MAX(version)} 会漏——
 *       "已有一条 v3，又改了另一条 v1"时全局最大值不变。</li>
 *   <li>任务表同理，每跑一次多一行。</li>
 * </ul>
 * 两个 {@code COUNT(*)} 都是单表标量查询，比重新拼一整套事实便宜一个数量级。
 *
 * <p>两个已知的不精确，都靠 30 秒 TTL 兜住，不额外加失效回调：
 * 规则的<b>状态迁移</b>（草稿→生效）不改变行数，最长 30 秒内卡片会继续标"（草稿）"；
 * 任务的<b>状态变化</b>（RUNNING→SUCCESS）也一样。这两件事都不改"谁能被引用"，
 * 只影响显示，晚 30 秒看到不构成错误决策——而加回调意味着每个写路径都要记得通知缓存，
 * 漏一处就是永久脏数据，那才是真的错。
 */
@Component
public class FactsCache {

    private static final Logger log = LoggerFactory.getLogger(FactsCache.class);

    /** 默认活 30 秒：够覆盖一次多轮对话，又不至于让"刚改完规则"半小时看不到。 */
    static final long DEFAULT_TTL_MS = 30_000L;

    private final RuleRepository rules;
    private final ReconRepository repository;
    private final LongSupplier clock;
    private final long ttlMs;

    private String key;
    private ContextFacts value;
    private long expiresAt;

    @Autowired
    public FactsCache(RuleRepository rules, ReconRepository repository) {
        this(rules, repository, System::currentTimeMillis, DEFAULT_TTL_MS);
    }

    /** 测试用：可注入时钟与 TTL。 */
    FactsCache(RuleRepository rules, ReconRepository repository, LongSupplier clock, long ttlMs) {
        this.rules = rules;
        this.repository = repository;
        this.clock = clock;
        this.ttlMs = ttlMs;
    }

    /** 当前失效键；算它本身是两次标量查询，不碰任何正文。 */
    public String revision() {
        return rules.ruleCounter() + ":" + repository.jobCounter();
    }

    /**
     * 取事实：键没变且没过期就给缓存，否则调 {@code builder} 重建。
     *
     * <p>{@code synchronized}：一个会话被两个线程同时跑时，宁可让第二个等一次重建，
     * 也不要两边同时去拼同一份字符串（拼出来一样，白花两遍库）。
     */
    public synchronized ContextFacts get(String revision, Supplier<ContextFacts> builder) {
        long now = clock.getAsLong();
        if (value != null && now < expiresAt && Objects.equals(revision, key)) {
            return value;
        }
        ContextFacts fresh = builder.get();
        key = revision;
        value = fresh;
        expiresAt = now + ttlMs;
        log.info("系统事实重建：key={}（规则/任务计数变了或缓存过期）", revision);
        return fresh;
    }

    /** 手工失效：规则改完后要立刻看到，而不是等 30 秒。 */
    public synchronized void invalidate() {
        value = null;
        key = null;
    }
}
