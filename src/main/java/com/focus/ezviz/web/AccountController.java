package com.focus.ezviz.web;

import com.focus.ezviz.domain.EzvizAccount;
import com.focus.ezviz.service.EzvizAccountService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/accounts")
public class AccountController {

    private final EzvizAccountService accountService;

    public AccountController(EzvizAccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("accounts", accountService.findAll());
        model.addAttribute("account", new EzvizAccount());
        return "accounts";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        model.addAttribute("accounts", accountService.findAll());
        model.addAttribute("account", accountService.get(id));
        return "accounts";
    }

    @PostMapping
    public String save(@ModelAttribute EzvizAccount form, RedirectAttributes redirect) {
        try {
            EzvizAccount account;
            if (form.getId() != null) {
                account = accountService.get(form.getId());
            } else {
                account = new EzvizAccount();
            }
            account.setName(form.getName());
            account.setAppKey(form.getAppKey());
            account.setAppSecret(form.getAppSecret());
            account.setEnabled(form.isEnabled());
            if (form.getAccessToken() != null && !form.getAccessToken().isBlank()) {
                account.setAccessToken(form.getAccessToken().trim());
            }
            accountService.save(account);
            redirect.addFlashAttribute("message", "萤石账号已保存");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", "保存失败：" + e.getMessage());
        }
        return "redirect:/accounts";
    }

    @PostMapping("/{id}/test")
    public String test(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            int count = accountService.testConnection(id);
            redirect.addFlashAttribute("message", "连接成功，账号下共有 " + count + " 个设备");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", "连接失败：" + e.getMessage());
        }
        return "redirect:/accounts";
    }

    @PostMapping("/{id}/sync")
    public String sync(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            int added = accountService.syncCameras(id);
            redirect.addFlashAttribute("message", "设备同步完成，新增 " + added + " 个摄像头");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", "同步失败：" + e.getMessage());
        }
        return "redirect:/cameras";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            accountService.delete(id);
            redirect.addFlashAttribute("message", "账号已删除");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", "删除失败：" + e.getMessage());
        }
        return "redirect:/accounts";
    }
}
