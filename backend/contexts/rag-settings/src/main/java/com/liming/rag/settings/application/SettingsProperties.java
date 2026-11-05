package com.liming.rag.settings.application;


import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 系统设置的配置（{@code rag.settings}）。
 *
 * @param masterKey 加密服务商密钥用的主密钥（环境变量 {@code RAG_MASTER_KEY}）：32 字节随机值的 Base64，
 *     可用 {@code openssl rand -base64 32} 生成；为空时不能在界面保存密钥（D-043）。不进日志。
 * @param industryPack 用哪个行业包（环境变量 {@code RAG_INDUSTRY_PACK}），即 {@code industry-packs/<名字>/}；
 *     找不到时启动失败（D-045）
 * @param branding 部署时给的产品名称和登录页文案（D-042），作系统设置里品牌三项的默认值；留空的项用内核默认值
 * @param statsZone 使用统计的“一天”按哪个时区划分（环境变量 {@code RAG_STATS_ZONE}），部署所在地区的属性，不属于行业包
 */
@ConfigurationProperties("rag.settings")
public record SettingsProperties(@DefaultValue("") String masterKey, @DefaultValue("retail") String industryPack,
        @DefaultValue Branding branding, @DefaultValue("Asia/Shanghai") String statsZone) {

    /** 只关心主密钥的地方（如单元测试）用默认的行业包和品牌。 */
    public SettingsProperties(String masterKey) {
        this(masterKey, "retail", new Branding("", "", ""), "Asia/Shanghai");
    }

    @ConstructorBinding
    public SettingsProperties {
        masterKey = masterKey == null ? "" : masterKey.strip();
        if (!masterKey.isEmpty()) {
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(masterKey);
            } catch (IllegalArgumentException e) {
                throw invalid();
            }
            if (decoded.length != 32) {
                throw invalid();
            }
        }
        industryPack = industryPack == null ? "" : industryPack.strip();
        if (!industryPack.matches("[a-z][a-z0-9-]{0,39}")) {
            throw new IllegalArgumentException("rag.settings.industry-pack 只能是小写字母、数字和连字符，如 retail");
        }
        branding = branding == null ? new Branding("", "", "") : branding;
        try {
            ZoneId.of(statsZone == null ? "" : statsZone.strip());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("rag.settings.stats-zone 不是有效的时区，如 Asia/Shanghai");
        }
        statsZone = statsZone.strip();
    }

    /**
     * 部署配置的品牌（环境变量 {@code RAG_BRAND_*}）。去掉首尾空格，留空表示没配置；超长时启动失败。
     *
     * @param name 产品名称
     * @param tagline 登录页名称下方的一句话
     * @param loginHint 登录按钮下方的提示
     */
    public record Branding(@DefaultValue("") String name, @DefaultValue("") String tagline,
            @DefaultValue("") String loginHint) {

        public Branding {
            name = checked(name, SettingKey.BRAND_NAME, "name");
            tagline = checked(tagline, SettingKey.BRAND_TAGLINE, "tagline");
            loginHint = checked(loginHint, SettingKey.LOGIN_HINT, "login-hint");
        }

        /** 某一项部署时配置的值，没配置时为空串。 */
        public String of(SettingKey key) {
            return switch (key) {
                case BRAND_NAME -> name;
                case BRAND_TAGLINE -> tagline;
                case LOGIN_HINT -> loginHint;
                default -> throw new IllegalArgumentException(key + " 不是品牌设置");
            };
        }

        private static String checked(String value, SettingKey key, String property) {
            var trimmed = value == null ? "" : value.strip();
            if (trimmed.length() > key.maxLength()) {
                throw new IllegalArgumentException(
                        "rag.settings.branding." + property + " 最多 " + key.maxLength() + " 个字符");
            }
            return trimmed;
        }
    }

    public boolean masterKeyConfigured() {
        return !masterKey.isEmpty();
    }

    /** 不输出主密钥。 */
    @Override
    public String toString() {
        return "SettingsProperties[masterKeyConfigured=" + masterKeyConfigured() + ", industryPack=" + industryPack
                + ", branding=" + branding + ", statsZone=" + statsZone + "]";
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "RAG_MASTER_KEY 必须是 32 字节随机值的 Base64，可用 openssl rand -base64 32 生成");
    }
}
