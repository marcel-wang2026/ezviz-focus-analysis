package com.focus.ezviz.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用配置，映射 application.yml 中的 app.* 配置项。
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String timezone = "Asia/Shanghai";
    private Storage storage = new Storage();
    private Ezviz ezviz = new Ezviz();
    private Analyzer analyzer = new Analyzer();
    private Scheduler scheduler = new Scheduler();
    private Demo demo = new Demo();

    public static class Storage {
        /** 视频归档根目录 */
        private String root = "./data/videos";
        /** 默认保留天数，0 表示永久保留 */
        private int retentionDays = 30;

        public String getRoot() { return root; }
        public void setRoot(String root) { this.root = root; }
        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    }

    public static class Ezviz {
        private String baseUrl = "https://open.ys7.com";
        private int connectTimeoutSeconds = 10;
        private int readTimeoutSeconds = 60;
        private int maxRangeMinutes = 2880;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(int v) { this.connectTimeoutSeconds = v; }
        public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
        public void setReadTimeoutSeconds(int v) { this.readTimeoutSeconds = v; }
        public int getMaxRangeMinutes() { return maxRangeMinutes; }
        public void setMaxRangeMinutes(int v) { this.maxRangeMinutes = v; }
    }

    public static class Analyzer {
        /** cli | http */
        private String mode = "cli";
        private String python = "python3";
        private String script = "./analyzer/analyze.py";
        private String httpUrl = "http://127.0.0.1:9001/analyze";
        private long timeoutSeconds = 3600;
        private double sampleFps = 1;
        private String tasks = "focus,distraction,hands,phone,audio";
        private String lang = "zh";

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getPython() { return python; }
        public void setPython(String python) { this.python = python; }
        public String getScript() { return script; }
        public void setScript(String script) { this.script = script; }
        public String getHttpUrl() { return httpUrl; }
        public void setHttpUrl(String httpUrl) { this.httpUrl = httpUrl; }
        public long getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(long v) { this.timeoutSeconds = v; }
        public double getSampleFps() { return sampleFps; }
        public void setSampleFps(double v) { this.sampleFps = v; }
        public String getTasks() { return tasks; }
        public void setTasks(String tasks) { this.tasks = tasks; }
        public String getLang() { return lang; }
        public void setLang(String lang) { this.lang = lang; }
    }

    public static class Scheduler {
        private boolean enabled = true;
        private int poolSize = 3;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getPoolSize() { return poolSize; }
        public void setPoolSize(int v) { this.poolSize = v; }
    }

    public static class Demo {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public Storage getStorage() { return storage; }
    public void setStorage(Storage storage) { this.storage = storage; }
    public Ezviz getEzviz() { return ezviz; }
    public void setEzviz(Ezviz ezviz) { this.ezviz = ezviz; }
    public Analyzer getAnalyzer() { return analyzer; }
    public void setAnalyzer(Analyzer analyzer) { this.analyzer = analyzer; }
    public Scheduler getScheduler() { return scheduler; }
    public void setScheduler(Scheduler scheduler) { this.scheduler = scheduler; }
    public Demo getDemo() { return demo; }
    public void setDemo(Demo demo) { this.demo = demo; }
}
