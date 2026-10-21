package com.focus.ezviz.web;

import com.focus.ezviz.domain.Camera;
import com.focus.ezviz.service.CameraService;
import com.focus.ezviz.service.DownloadExecutor;
import com.focus.ezviz.service.EzvizAccountService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;

@Controller
@RequestMapping("/cameras")
public class CameraController {

    private final CameraService cameraService;
    private final EzvizAccountService accountService;
    private final DownloadExecutor downloadExecutor;

    public CameraController(CameraService cameraService,
                            EzvizAccountService accountService,
                            DownloadExecutor downloadExecutor) {
        this.cameraService = cameraService;
        this.accountService = accountService;
        this.downloadExecutor = downloadExecutor;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("cameras", cameraService.findAll());
        model.addAttribute("accounts", accountService.findAll());
        model.addAttribute("camera", new Camera());
        return "cameras";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        model.addAttribute("cameras", cameraService.findAll());
        model.addAttribute("accounts", accountService.findAll());
        model.addAttribute("camera", cameraService.get(id));
        return "cameras";
    }

    @PostMapping
    public String save(@ModelAttribute Camera form, RedirectAttributes redirect) {
        try {
            Camera camera = form.getId() != null ? cameraService.get(form.getId()) : new Camera();
            camera.setAccountId(form.getAccountId());
            camera.setDeviceSerial(form.getDeviceSerial() == null ? "" : form.getDeviceSerial().trim());
            camera.setChannelNo(Math.max(form.getChannelNo(), 1));
            camera.setName(form.getName());
            camera.setRoi(form.getRoi());
            camera.setRemark(form.getRemark());
            camera.setEnabled(form.isEnabled());
            camera.setAnalysisEnabled(form.isAnalysisEnabled());
            cameraService.save(camera);
            redirect.addFlashAttribute("message", "摄像头配置已保存");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", "保存失败：" + e.getMessage());
        }
        return "redirect:/cameras";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            cameraService.delete(id);
            redirect.addFlashAttribute("message", "摄像头已删除");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", "删除失败：" + e.getMessage());
        }
        return "redirect:/cameras";
    }

    /** 立即下载过去 N 分钟的录像，并自动分析 */
    @PostMapping("/{id}/download")
    public String downloadNow(@PathVariable Long id,
                              @RequestParam(defaultValue = "30") int minutes,
                              @RequestParam(defaultValue = "focus,distraction,hands,phone,audio") String tasks,
                              RedirectAttributes redirect) {
        LocalDateTime to = LocalDateTime.now().withNano(0);
        LocalDateTime from = to.minusMinutes(Math.max(1, Math.min(minutes, 2880)));
        downloadExecutor.runManual(id, from, to, true, tasks);
        redirect.addFlashAttribute("message",
                "已提交下载任务：" + from + " ~ " + to + "，下载完成后将自动提交分析");
        return "redirect:/videos";
    }
}
