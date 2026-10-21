package com.focus.ezviz.service;

import com.focus.ezviz.domain.Camera;
import com.focus.ezviz.domain.EzvizAccount;
import com.focus.ezviz.ezviz.EzvizClient;
import com.focus.ezviz.repository.CameraRepository;
import com.focus.ezviz.repository.EzvizAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 萤石账号管理：保存、连通性测试、摄像头同步 */
@Service
public class EzvizAccountService {

    private static final Logger log = LoggerFactory.getLogger(EzvizAccountService.class);

    private final EzvizAccountRepository accountRepository;
    private final CameraRepository cameraRepository;
    private final EzvizClient ezvizClient;

    public EzvizAccountService(EzvizAccountRepository accountRepository,
                               CameraRepository cameraRepository,
                               EzvizClient ezvizClient) {
        this.accountRepository = accountRepository;
        this.cameraRepository = cameraRepository;
        this.ezvizClient = ezvizClient;
    }

    public List<EzvizAccount> findAll() {
        return accountRepository.findAll();
    }

    public EzvizAccount get(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("萤石账号不存在: " + id));
    }

    public EzvizAccount save(EzvizAccount account) {
        if (account.getAppKey() == null) {
            account.setAppKey("");
        }
        if (account.getAppSecret() == null) {
            account.setAppSecret("");
        }
        account.setUpdatedAt(LocalDateTime.now());
        return accountRepository.save(account);
    }

    /**
     * 测试连接并刷新 token。
     *
     * @return 设备数量
     */
    public int testConnection(Long id) {
        EzvizAccount account = get(id);
        try {
            var devices = ezvizClient.listDevices(account);
            account.setLastCheckAt(LocalDateTime.now());
            account.setLastCheckMessage("连接成功，共 " + devices.size() + " 个设备");
            accountRepository.save(account);
            return devices.size();
        } catch (Exception e) {
            account.setLastCheckAt(LocalDateTime.now());
            account.setLastCheckMessage("连接失败：" + e.getMessage());
            accountRepository.save(account);
            throw e;
        }
    }

    /**
     * 从萤石同步设备列表，已存在的设备更新名称，不覆盖用户配置的 ROI / 分析开关。
     *
     * @return 新增数量
     */
    public int syncCameras(Long accountId) {
        EzvizAccount account = get(accountId);
        var devices = ezvizClient.listDevices(account);
        int added = 0;
        for (EzvizClient.EzvizDevice device : devices) {
            Camera camera = cameraRepository
                    .findByAccountIdAndDeviceSerialAndChannelNo(accountId, device.deviceSerial(), device.channelNo())
                    .orElse(null);
            if (camera == null) {
                camera = new Camera();
                camera.setAccountId(accountId);
                camera.setDeviceSerial(device.deviceSerial());
                camera.setChannelNo(device.channelNo());
                camera.setName(device.deviceName());
                camera.setEnabled(true);
                camera.setAnalysisEnabled(true);
                added++;
            } else if (camera.getName() == null || camera.getName().isBlank()) {
                camera.setName(device.deviceName());
            }
            cameraRepository.save(camera);
        }
        account.setLastCheckAt(LocalDateTime.now());
        account.setLastCheckMessage("同步成功，共 " + devices.size() + " 个设备，新增 " + added + " 个");
        accountRepository.save(account);
        log.info("萤石账号[{}] 同步设备完成，共 {} 个，新增 {}", account.getName(), devices.size(), added);
        return added;
    }

    public List<EzvizClient.EzvizDevice> listRemoteDevices(Long accountId) {
        return ezvizClient.listDevices(get(accountId));
    }

    public void delete(Long id) {
        long cameras = cameraRepository.findByAccountIdOrderByIdAsc(id).size();
        if (cameras > 0) {
            throw new IllegalStateException("该账号下还有 " + cameras + " 个摄像头，请先删除摄像头");
        }
        accountRepository.deleteById(id);
    }
}
