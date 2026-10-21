package com.focus.ezviz.web;

import com.focus.ezviz.config.RuleScheduler;
import com.focus.ezviz.domain.DownloadRule;
import com.focus.ezviz.repository.CameraRepository;
import com.focus.ezviz.repository.DownloadRuleRepository;
import com.focus.ezviz.service.CameraService;
import com.focus.ezviz.service.DownloadExecutor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/rules")
public class RuleController {

    private final DownloadRuleRepository ruleRepository;
    private final CameraRepository cameraRepository;
    private final CameraService cameraService;
    private final DownloadExecutor downloadExecutor;
    private final RuleScheduler ruleScheduler;

    public RuleController(DownloadRuleRepository ruleRepository, CameraRepository cameraRepository,
                          CameraService cameraService, DownloadExecutor downloadExecutor,
                          RuleScheduler ruleScheduler) {
        this.ruleRepository = ruleRepository;
        this.cameraRepository = cameraRepository;
        this.cameraService = cameraService;
        this.downloadExecutor = downloadExecutor;
        this.ruleScheduler = ruleScheduler;
    }

    @GetMapping
    public String list(Model model) {
        List<DownloadRule> rules = ruleRepository.findAllByOrderByIdAsc();
        model.addAttribute("rules", rules);
        model.addAttribute("cameras", cameraService.findAll());
        model.addAttribute("cameraNames", cameraNames());
        model.addAttribute("rule", new DownloadRule());
        model.addAttribute("schedulerEnabled", true);
        return "rules";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        model.addAttribute("rules", ruleRepository.findAllByOrderByIdAsc());
        model.addAttribute("cameras", cameraService.findAll());
        model.addAttribute("cameraNames", cameraNames());
        model.addAttribute("rule", ruleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("规则不存在: " + id)));
        return "rules";
    }

    @PostMapping
    public String save(@ModelAttribute DownloadRule form, RedirectAttributes redirect) {
        try {
            String cron = form.getCron() == null ? "" : form.getCron().trim();
            if (!CronExpression.isValidExpression(cron)) {
                throw new IllegalArgumentException("cron 表达式不合法，示例：0 0/30 * * * *");
            }
            DownloadRule rule = form.getId() != null
                    ? ruleRepository.findById(form.getId()).orElseThrow()
                    : new DownloadRule();
            rule.setName(form.getName());
            rule.setCameraId(form.getCameraId());
            rule.setEnabled(form.isEnabled());
            rule.setCron(cron);
            rule.setWindowMinutes(Math.max(form.getWindowMinutes(), 1));
            rule.setSegmentMinutes(Math.max(form.getSegmentMinutes(), 1));
            rule.setRetentionDays(Math.max(form.getRetentionDays(), 0));
            rule.setAutoAnalyze(form.isAutoAnalyze());
            rule.setAnalyzeTasks(form.getAnalyzeTasks());
            ruleRepository.save(rule);
            ruleScheduler.refresh();
            redirect.addFlashAttribute("message", "下载规则已保存并生效");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", "保存失败：" + e.getMessage());
        }
        return "redirect:/rules";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            ruleRepository.deleteById(id);
            ruleScheduler.refresh();
            redirect.addFlashAttribute("message", "规则已删除");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", "删除失败：" + e.getMessage());
        }
        return "redirect:/rules";
    }

    @PostMapping("/{id}/run")
    public String runNow(@PathVariable Long id, RedirectAttributes redirect) {
        if (ruleRepository.findById(id).isEmpty()) {
            redirect.addFlashAttribute("error", "规则不存在");
            return "redirect:/rules";
        }
        downloadExecutor.runRule(id);
        redirect.addFlashAttribute("message", "已触发规则执行，请稍后到“视频库”查看结果");
        return "redirect:/rules";
    }

    private java.util.Map<Long, String> cameraNames() {
        java.util.Map<Long, String> names = new java.util.HashMap<>();
        cameraRepository.findAll().forEach(c -> names.put(c.getId(), c.getName()));
        return names;
    }
}
