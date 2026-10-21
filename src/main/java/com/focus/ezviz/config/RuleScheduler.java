package com.focus.ezviz.config;

import com.focus.ezviz.domain.DownloadRule;
import com.focus.ezviz.repository.DownloadRuleRepository;
import com.focus.ezviz.service.DownloadExecutor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

/**
 * 动态下载规则调度：为每条启用的规则注册独立 cron 触发器，规则变更后热刷新。
 */
@Component
public class RuleScheduler {

    private static final Logger log = LoggerFactory.getLogger(RuleScheduler.class);

    private final DownloadRuleRepository ruleRepository;
    private final DownloadExecutor downloadExecutor;
    private final AppProperties props;
    private final ThreadPoolTaskScheduler scheduler;
    private final Map<Long, ScheduledFuture<?>> futures = new HashMap<>();
    private final Set<Long> invalidRules = new HashSet<>();

    public RuleScheduler(DownloadRuleRepository ruleRepository, DownloadExecutor downloadExecutor,
                         AppProperties props) {
        this.ruleRepository = ruleRepository;
        this.downloadExecutor = downloadExecutor;
        this.props = props;
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(Math.max(props.getScheduler().getPoolSize(), 1));
        this.scheduler.setThreadNamePrefix("rule-scheduler-");
        this.scheduler.setWaitForTasksToCompleteOnShutdown(false);
        this.scheduler.initialize();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        refresh();
    }

    /** 重新加载全部规则（新增/修改/删除后调用） */
    public synchronized void refresh() {
        futures.values().forEach(f -> f.cancel(false));
        futures.clear();
        invalidRules.clear();
        if (!props.getScheduler().isEnabled()) {
            log.info("调度器已关闭（app.scheduler.enabled=false）");
            return;
        }
        ZoneId zone = ZoneId.of(props.getTimezone());
        for (DownloadRule rule : ruleRepository.findByEnabledTrueOrderByIdAsc()) {
            try {
                CronTrigger trigger = new CronTrigger(rule.getCron(), zone);
                Long ruleId = rule.getId();
                ScheduledFuture<?> future = scheduler.schedule(
                        () -> downloadExecutor.runRule(ruleId), trigger);
                if (future != null) {
                    futures.put(ruleId, future);
                }
            } catch (IllegalArgumentException e) {
                invalidRules.add(rule.getId());
                log.warn("规则[{}] cron 表达式非法: {}", rule.getId(), rule.getCron());
            }
        }
        log.info("已加载 {} 条下载规则调度", futures.size());
    }

    public boolean isScheduled(Long ruleId) {
        return futures.containsKey(ruleId);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
    }
}
