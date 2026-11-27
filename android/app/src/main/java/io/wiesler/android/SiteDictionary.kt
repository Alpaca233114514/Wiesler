package io.wiesler.android

import java.util.Locale

data class SiteMatch(
    val id: String,
    val name: String,
    val url: String,
    val category: String,
    val matchedDomain: String,
    val aliases: List<String> = emptyList()
)

private data class SiteEntry(
    val id: String,
    val name: String,
    val url: String,
    val category: String,
    val aliases: List<String>,
    val domains: List<String>,
    val ips: List<String> = emptyList()
)

// 离线「域名 / 公共 DNS IP → 网站或服务」字典；仅用于界面可读展示，保留原始抓包域名证据。
// Offline domain/public-DNS-IP to site/service dictionary for readable UI display; raw host evidence is preserved.
object SiteDictionary {
    private val catalog: List<SiteEntry> = listOf(
        // --- 国内主流视频 / 内容 / 社交 ---
        SiteEntry(
            id = "bilibili",
            name = "哔哩哔哩",
            url = "bilibili.com",
            category = "视频",
            aliases = listOf("b站", "bili", "bilibili", "小破站"),
            domains = listOf(
                "bilibili.com", "bilibili.tv", "bilivideo.com", "bilivideo.cn", "bilivideo.net",
                "hdslb.com", "biliapi.com", "biliapi.net", "biligame.com", "biligame.net",
                "b23.tv", "acg.tv", "acgvideo.com", "bilicomic.com", "bilicomics.com", "im9.com"
            )
        ),
        SiteEntry(
            id = "wechat",
            name = "微信",
            url = "weixin.qq.com",
            category = "社交",
            aliases = listOf("wx", "wechat", "weixin", "公众号", "朋友圈"),
            domains = listOf(
                "weixin.qq.com", "weixin.qq.com.cn", "wechat.com", "servicewechat.com",
                "wx.qq.com", "wx.qlogo.cn", "wx.gtimg.com", "mmbiz.qpic.cn", "mmbiz.qlogo.cn",
                "mmsns.qpic.cn", "res.wx.qq.com", "mp.weixin.qq.com", "channels.weixin.qq.com",
                "finder.video.qq.com", "weixinbridge.com", "tenpay.com"
            )
        ),
        SiteEntry(
            id = "wecom",
            name = "企业微信",
            url = "work.weixin.qq.com",
            category = "办公",
            aliases = listOf("企微", "wecom", "wxwork"),
            domains = listOf("work.weixin.qq.com", "wework.qpic.cn", "wwcdn.weixin.qq.com")
        ),
        SiteEntry(
            id = "tencent-video",
            name = "腾讯视频",
            url = "v.qq.com",
            category = "视频",
            aliases = listOf("wetv", "tencent video"),
            domains = listOf("v.qq.com", "video.qq.com", "wetv.vip", "vv.video.qq.com", "vm.gtimg.cn")
        ),
        SiteEntry(
            id = "qq-music",
            name = "QQ 音乐",
            url = "y.qq.com",
            category = "音乐",
            aliases = listOf("qqmusic"),
            domains = listOf("y.qq.com", "music.qq.com", "aqqmusic.tc.qq.com", "y.gtimg.cn")
        ),
        SiteEntry(
            id = "tencent-cloud",
            name = "腾讯云",
            url = "cloud.tencent.com",
            category = "云服务",
            aliases = listOf("qcloud", "dnspod"),
            domains = listOf(
                "cloud.tencent.com", "qcloud.com", "myqcloud.com", "tencent-cloud.net",
                "tencentcloudapi.com", "tencentyun.com", "dnspod.cn", "dnspod.net", "doh.pub"
            )
        ),
        SiteEntry(
            id = "qq-tencent",
            name = "腾讯 / QQ",
            url = "qq.com",
            category = "社交",
            aliases = listOf("tencent", "qq", "鹅厂"),
            domains = listOf("qq.com", "tencent.com", "gtimg.cn", "gtimg.com", "qpic.cn", "qlogo.cn", "idqqimg.com", "myapp.com", "qqmail.com")
        ),
        SiteEntry(
            id = "douyin",
            name = "抖音",
            url = "douyin.com",
            category = "视频",
            aliases = listOf("dy", "douyin"),
            domains = listOf(
                "douyin.com", "douyincdn.com", "douyinpic.com", "douyinvod.com", "douyinliving.com",
                "douyinstatic.com", "iesdouyin.com", "amemv.com", "aweme.snssdk.com"
            )
        ),
        SiteEntry(
            id = "feishu",
            name = "飞书",
            url = "feishu.cn",
            category = "办公",
            aliases = listOf("lark", "feishu"),
            domains = listOf("feishu.cn", "feishu.net", "feishucdn.com", "larksuite.com", "larkoffice.com")
        ),
        SiteEntry(
            id = "doubao",
            name = "豆包 AI",
            url = "doubao.com",
            category = "AI",
            aliases = listOf("doubao", "coze", "扣子"),
            domains = listOf("doubao.com", "coze.cn", "coze.com", "volces.com")
        ),
        SiteEntry(
            id = "bytedance",
            name = "字节跳动 / 头条",
            url = "toutiao.com",
            category = "资讯",
            aliases = listOf("byte", "bytedance", "今日头条", "西瓜视频"),
            domains = listOf(
                "bytedance.com", "bytedance.net", "byteimg.com", "bytetos.com", "bytedns.net",
                "bytecdn.cn", "bytegoofy.com", "byteoversea.com", "ibytedtos.com", "snssdk.com",
                "toutiao.com", "toutiaocdn.com", "toutiaopic.com", "ixigua.com", "pstatp.com",
                "zjcdn.com", "volcengine.com", "volccdn.com"
            )
        ),
        SiteEntry(
            id = "xiaohongshu",
            name = "小红书",
            url = "xiaohongshu.com",
            category = "社交",
            aliases = listOf("xhs", "rednote", "redbook"),
            domains = listOf("xiaohongshu.com", "xhscdn.com", "xhscdn.net", "xhslink.com", "fengkongcloud.com")
        ),
        SiteEntry(
            id = "zhihu",
            name = "知乎",
            url = "zhihu.com",
            category = "社区",
            aliases = listOf("zhihu"),
            domains = listOf("zhihu.com", "zhimg.com")
        ),
        SiteEntry(
            id = "weibo",
            name = "微博",
            url = "weibo.com",
            category = "社交",
            aliases = listOf("sina", "weibo", "新浪"),
            domains = listOf("weibo.com", "weibo.cn", "weibocdn.com", "sinaimg.cn", "sinajs.cn", "sina.com.cn")
        ),
        SiteEntry(
            id = "douban",
            name = "豆瓣",
            url = "douban.com",
            category = "社区",
            aliases = listOf("douban"),
            domains = listOf("douban.com", "doubanio.com")
        ),
        SiteEntry(
            id = "kuaishou",
            name = "快手",
            url = "kuaishou.com",
            category = "视频",
            aliases = listOf("ks", "kuaishou"),
            domains = listOf("kuaishou.com", "kwai.com", "kwaicdn.com", "yximgs.com", "gifshow.com", "ksapisrv.com")
        ),
        SiteEntry(
            id = "iqiyi",
            name = "爱奇艺",
            url = "iqiyi.com",
            category = "视频",
            aliases = listOf("iqiyi", "qiyi"),
            domains = listOf("iqiyi.com", "qiyi.com", "iqiyipic.com", "qy.net", "iq.com")
        ),
        SiteEntry(
            id = "youku",
            name = "优酷",
            url = "youku.com",
            category = "视频",
            aliases = listOf("youku"),
            domains = listOf("youku.com", "ykimg.com", "tudou.com", "soku.com")
        ),
        SiteEntry(
            id = "mangotv",
            name = "芒果 TV",
            url = "mgtv.com",
            category = "视频",
            aliases = listOf("mgtv"),
            domains = listOf("mgtv.com", "hunantv.com", "hitv.com")
        ),
        SiteEntry(
            id = "netease-music",
            name = "网易云音乐",
            url = "music.163.com",
            category = "音乐",
            aliases = listOf("云村", "网易云", "163music"),
            domains = listOf("music.163.com", "music.126.net")
        ),
        SiteEntry(
            id = "netease",
            name = "网易",
            url = "163.com",
            category = "资讯",
            aliases = listOf("netease", "163"),
            domains = listOf("163.com", "126.com", "126.net", "netease.com", "netease.im", "ydstatic.com", "youdao.com", "lofter.com")
        ),
        SiteEntry(
            id = "taobao",
            name = "淘宝 / 天猫",
            url = "taobao.com",
            category = "购物",
            aliases = listOf("tb", "tmall", "taobao", "闲鱼", "淘系"),
            domains = listOf("taobao.com", "tmall.com", "tbcdn.cn", "taobaocdn.com", "goofish.com", "etao.com", "mmstat.com", "tanx.com", "cainiao.com")
        ),
        SiteEntry(
            id = "alipay",
            name = "支付宝",
            url = "alipay.com",
            category = "支付",
            aliases = listOf("zfb", "alipay", "蚂蚁"),
            domains = listOf("alipay.com", "alipayobjects.com", "alipaydns.com", "antgroup.com", "antfin.com")
        ),
        SiteEntry(
            id = "dingtalk",
            name = "钉钉",
            url = "dingtalk.com",
            category = "办公",
            aliases = listOf("dingtalk", "钉钉"),
            domains = listOf("dingtalk.com", "dingtalkapps.com")
        ),
        SiteEntry(
            id = "qwen",
            name = "通义千问",
            url = "tongyi.aliyun.com",
            category = "AI",
            aliases = listOf("qwen", "tongyi", "千问"),
            domains = listOf("qwen.ai", "qwenlm.ai", "tongyi.aliyun.com", "dashscope.aliyuncs.com")
        ),
        SiteEntry(
            id = "aliyun",
            name = "阿里云 / 阿里",
            url = "aliyun.com",
            category = "云服务",
            aliases = listOf("alibaba", "aliyun", "alidns"),
            domains = listOf("aliyun.com", "aliyuncs.com", "alicdn.com", "alidns.com", "alibaba.com", "alibabagroup.com", "alibabacloud.com", "1688.com", "aliyundrive.com", "alipan.com")
        ),
        SiteEntry(
            id = "amap",
            name = "高德地图",
            url = "amap.com",
            category = "地图",
            aliases = listOf("gaode", "amap"),
            domains = listOf("amap.com", "autonavi.com")
        ),
        SiteEntry(
            id = "jd",
            name = "京东",
            url = "jd.com",
            category = "购物",
            aliases = listOf("jd", "jingdong"),
            domains = listOf("jd.com", "360buyimg.com", "360buy.com", "jdcloud.com", "jcloudcs.com", "jdpay.com", "jdl.com")
        ),
        SiteEntry(
            id = "pinduoduo",
            name = "拼多多",
            url = "pinduoduo.com",
            category = "购物",
            aliases = listOf("pdd", "temu"),
            domains = listOf("pinduoduo.com", "yangkeduo.com", "pddpic.com", "temu.com")
        ),
        SiteEntry(
            id = "meituan",
            name = "美团 / 点评",
            url = "meituan.com",
            category = "生活",
            aliases = listOf("meituan", "大众点评"),
            domains = listOf("meituan.com", "meituan.net", "dianping.com", "dpfile.com", "sankuai.com")
        ),
        SiteEntry(
            id = "baidu",
            name = "百度",
            url = "baidu.com",
            category = "搜索",
            aliases = listOf("baidu", "百度网盘", "文心"),
            domains = listOf("baidu.com", "bdstatic.com", "bdimg.com", "bcebos.com", "baidupcs.com", "baidubce.com", "baidustatic.com", "hao123.com")
        ),
        SiteEntry(
            id = "deepseek",
            name = "DeepSeek",
            url = "chat.deepseek.com",
            category = "AI",
            aliases = listOf("deepseek", "深度求索"),
            domains = listOf("deepseek.com")
        ),
        SiteEntry(
            id = "kimi",
            name = "Kimi",
            url = "kimi.moonshot.cn",
            category = "AI",
            aliases = listOf("kimi", "moonshot", "月之暗面"),
            domains = listOf("moonshot.cn", "kimi.ai", "kimi.com")
        ),
        SiteEntry(
            id = "wps",
            name = "WPS 办公",
            url = "wps.cn",
            category = "办公",
            aliases = listOf("wps", "金山"),
            domains = listOf("wps.cn", "wps.com", "kdocs.cn", "ksyun.com", "kingsoft.com")
        ),
        SiteEntry(
            id = "mihoyo",
            name = "米哈游",
            url = "mihoyo.com",
            category = "游戏",
            aliases = listOf("mihoyo", "hoyoverse", "原神", "崩坏"),
            domains = listOf("mihoyo.com", "hoyoverse.com", "hoyolab.com", "yuanshen.com", "bhsr.com")
        ),
        SiteEntry(
            id = "xiaomi",
            name = "小米",
            url = "mi.com",
            category = "系统",
            aliases = listOf("mi", "xiaomi", "miui", "hyperos"),
            domains = listOf("mi.com", "xiaomi.com", "xiaomi.net", "miui.com", "mifile.cn", "mi-img.com")
        ),
        SiteEntry(
            id = "huawei",
            name = "华为",
            url = "huawei.com",
            category = "系统",
            aliases = listOf("huawei", "harmony", "鸿蒙"),
            domains = listOf("huawei.com", "huaweicloud.com", "hicloud.com", "vmall.com", "dbankcdn.com", "honor.com", "hihonor.com")
        ),
        SiteEntry(
            id = "oppo-vivo",
            name = "OPPO / vivo",
            url = "oppo.com",
            category = "系统",
            aliases = listOf("oppo", "vivo", "oneplus", "一加", "realme"),
            domains = listOf("oppo.com", "oppomobile.com", "heytap.com", "heytapdl.com", "heytapimage.com", "oneplus.com", "vivo.com", "vivo.com.cn")
        ),
        SiteEntry(
            id = "juejin-csdn",
            name = "国内技术社区",
            url = "juejin.cn",
            category = "开发",
            aliases = listOf("掘金", "csdn", "gitee", "码云", "博客园"),
            domains = listOf("juejin.cn", "csdn.net", "csdnimg.cn", "gitee.com", "cnblogs.com", "oschina.net", "v2ex.com")
        ),

        // --- 国际主流 AI / 开发 / 科技 ---
        SiteEntry(
            id = "youtube",
            name = "YouTube",
            url = "youtube.com",
            category = "视频",
            aliases = listOf("yt", "ytb", "油管", "youtube"),
            domains = listOf(
                "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "ggpht.com",
                "youtube-nocookie.com", "youtubei.googleapis.com", "yt3.googleusercontent.com", "yt4.ggpht.com"
            )
        ),
        SiteEntry(
            id = "google-ai",
            name = "Google AI / Gemini",
            url = "gemini.google.com",
            category = "AI",
            aliases = listOf("gemini", "aistudio", "google ai", "antigravity"),
            domains = listOf(
                "gemini.google.com", "aistudio.google.com", "deepmind.google", "deepmind.com",
                "generativelanguage.googleapis.com", "aiplatform.googleapis.com",
                "aicode.googleapis.com", "cloudcode-pa.googleapis.com", "daily-cloudcode-pa.googleapis.com",
                "notebooklm.google.com", "labs.google"
            )
        ),
        SiteEntry(
            id = "gmail",
            name = "Gmail",
            url = "mail.google.com",
            category = "办公",
            aliases = listOf("gmail", "谷歌邮箱"),
            domains = listOf("mail.google.com", "gmail.com", "googlemail.com")
        ),
        SiteEntry(
            id = "google-play",
            name = "Google Play",
            url = "play.google.com",
            category = "应用商店",
            aliases = listOf("play", "google play", "谷歌商店"),
            domains = listOf("play.google.com", "play.googleapis.com", "play-fe.googleapis.com", "android.clients.google.com", "gvt1.com", "gvt2.com", "gvt3.com")
        ),
        SiteEntry(
            id = "google-drive",
            name = "Google 云端硬盘 / 文档",
            url = "drive.google.com",
            category = "办公",
            aliases = listOf("gdrive", "google docs", "google drive"),
            domains = listOf("drive.google.com", "docs.google.com", "sheets.google.com", "slides.google.com", "photos.google.com")
        ),
        SiteEntry(
            id = "google",
            name = "Google",
            url = "google.com",
            category = "搜索",
            aliases = listOf("google", "谷歌", "chrome", "gstatic", "android"),
            domains = listOf(
                "google.com", "google.com.hk", "google.co.jp", "google.cn", "googleapis.com",
                "gstatic.com", "googleusercontent.com", "googleadservices.com", "googlesyndication.com",
                "googletagmanager.com", "google-analytics.com", "doubleclick.net", "1e100.net",
                "g.co", "goo.gl", "android.com", "chromium.org", "firebaseio.com", "firebase.google.com",
                "withgoogle.com", "pki.goog", "dns.google"
            )
        ),
        SiteEntry(
            id = "openai",
            name = "OpenAI / ChatGPT",
            url = "chatgpt.com",
            category = "AI",
            aliases = listOf("openai", "chatgpt", "gpt", "codex"),
            domains = listOf("openai.com", "chatgpt.com", "oaistatic.com", "oaiusercontent.com", "sora.com")
        ),
        SiteEntry(
            id = "claude",
            name = "Claude / Anthropic",
            url = "claude.ai",
            category = "AI",
            aliases = listOf("claude", "anthropic"),
            domains = listOf("claude.ai", "anthropic.com", "claude.com")
        ),
        SiteEntry(
            id = "cursor",
            name = "Cursor AI",
            url = "cursor.com",
            category = "AI",
            aliases = listOf("cursor"),
            domains = listOf("cursor.com", "cursor.sh", "cursor-cdn.com")
        ),
        SiteEntry(
            id = "xai",
            name = "xAI / Grok",
            url = "grok.com",
            category = "AI",
            aliases = listOf("grok", "xai"),
            domains = listOf("x.ai", "grok.com")
        ),
        SiteEntry(
            id = "perplexity",
            name = "Perplexity",
            url = "perplexity.ai",
            category = "AI",
            aliases = listOf("perplexity", "pplx"),
            domains = listOf("perplexity.ai", "pplx.ai")
        ),
        SiteEntry(
            id = "huggingface",
            name = "Hugging Face",
            url = "huggingface.co",
            category = "AI",
            aliases = listOf("hf", "huggingface", "抱抱脸"),
            domains = listOf("huggingface.co", "hf.co", "hf.space")
        ),
        SiteEntry(
            id = "github",
            name = "GitHub",
            url = "github.com",
            category = "开发",
            aliases = listOf("gh", "github", "copilot"),
            domains = listOf(
                "github.com", "githubusercontent.com", "githubassets.com", "github.io",
                "github.dev", "ghcr.io", "githubcopilot.com", "githubstatus.com", "git.io"
            )
        ),
        SiteEntry(
            id = "gitlab",
            name = "GitLab",
            url = "gitlab.com",
            category = "开发",
            aliases = listOf("gitlab"),
            domains = listOf("gitlab.com", "gitlab.io", "gitlab-static.net")
        ),
        SiteEntry(
            id = "stackoverflow",
            name = "Stack Overflow",
            url = "stackoverflow.com",
            category = "开发",
            aliases = listOf("so", "stackoverflow"),
            domains = listOf("stackoverflow.com", "stackexchange.com", "sstatic.net")
        ),
        SiteEntry(
            id = "dev-registries",
            name = "开源包仓库 / 镜像",
            url = "npmjs.org",
            category = "开发",
            aliases = listOf("npm", "pypi", "docker", "crates", "rust", "maven", "gradle", "brew"),
            domains = listOf(
                "npmjs.org", "npmjs.com", "pypi.org", "pythonhosted.org", "docker.com", "docker.io",
                "crates.io", "rust-lang.org", "rustup.rs", "maven.org", "gradle.org", "brew.sh",
                "packagist.org", "rubygems.org", "golang.org", "go.dev", "nuget.org", "jsdelivr.net", "unpkg.com", "cdnjs.cloudflare.com"
            )
        ),
        SiteEntry(
            id = "jetbrains",
            name = "JetBrains",
            url = "jetbrains.com",
            category = "开发",
            aliases = listOf("idea", "pycharm", "jetbrains"),
            domains = listOf("jetbrains.com", "jetbrains.net", "intellij.net")
        ),
        SiteEntry(
            id = "ms-edge",
            name = "微软 Edge",
            url = "microsoft.com/edge",
            category = "浏览器",
            aliases = listOf("edge", "msedge"),
            domains = listOf("edge.microsoft.com", "msedge.net", "t-msedge.net", "a-msedge.net", "s-msedge.net", "b-msedge.net", "l-msedge.net", "edge.activity.windows.com")
        ),
        SiteEntry(
            id = "windows-update",
            name = "Windows 系统服务",
            url = "windows.com",
            category = "系统",
            aliases = listOf("windows", "winupdate", "msftconnecttest"),
            domains = listOf(
                "windowsupdate.com", "update.microsoft.com", "windows.com", "windows.net",
                "msftconnecttest.com", "msftncsi.com", "delivery.mp.microsoft.com", "settings-win.data.microsoft.com"
            )
        ),
        SiteEntry(
            id = "office-onedrive",
            name = "Microsoft 365 / OneDrive",
            url = "office.com",
            category = "办公",
            aliases = listOf("office", "onedrive", "outlook", "teams", "sharepoint"),
            domains = listOf("office.com", "office.net", "office365.com", "onedrive.com", "onedrive.live.com", "sharepoint.com", "outlook.com", "outlook.office365.com", "teams.microsoft.com", "live.com", "1drv.ms")
        ),
        SiteEntry(
            id = "microsoft",
            name = "微软 / Bing",
            url = "microsoft.com",
            category = "科技",
            aliases = listOf("ms", "microsoft", "bing", "azure", "vscode", "必应"),
            domains = listOf(
                "microsoft.com", "microsoftonline.com", "msn.com", "bing.com", "bing.net",
                "azure.com", "azureedge.net", "azurewebsites.net", "msecnd.net", "msocdn.com",
                "visualstudio.com", "vsassets.io", "xboxlive.com", "sfx.ms", "aka.ms"
            )
        ),
        SiteEntry(
            id = "icloud",
            name = "iCloud",
            url = "icloud.com",
            category = "云盘",
            aliases = listOf("icloud", "苹果云"),
            domains = listOf("icloud.com", "icloud.com.cn", "icloud-content.com", "me.com", "mac.com")
        ),
        SiteEntry(
            id = "apple-media",
            name = "App Store / Apple 媒体",
            url = "apps.apple.com",
            category = "应用商店",
            aliases = listOf("appstore", "itunes", "apple music"),
            domains = listOf("itunes.apple.com", "apps.apple.com", "mzstatic.com", "aaplimg.com", "music.apple.com", "tv.apple.com")
        ),
        SiteEntry(
            id = "apple",
            name = "苹果服务",
            url = "apple.com",
            category = "系统",
            aliases = listOf("apple", "ios", "macos", "苹果"),
            domains = listOf("apple.com", "apple.com.cn", "apple-dns.net", "cdn-apple.com", "apple-cloudkit.com", "push.apple.com", "ls-apple.com.akadns.net")
        ),
        SiteEntry(
            id = "x-twitter",
            name = "X (Twitter)",
            url = "x.com",
            category = "社交",
            aliases = listOf("twitter", "x", "推特"),
            domains = listOf("x.com", "twitter.com", "twimg.com", "t.co", "tweetdeck.com")
        ),
        SiteEntry(
            id = "telegram",
            name = "Telegram",
            url = "telegram.org",
            category = "社交",
            aliases = listOf("tg", "电报", "telegram"),
            domains = listOf("telegram.org", "telegram.me", "t.me", "tdesktop.com", "telegra.ph", "telesco.pe", "cdn-telegram.org")
        ),
        SiteEntry(
            id = "discord",
            name = "Discord",
            url = "discord.com",
            category = "社交",
            aliases = listOf("dc", "discord"),
            domains = listOf("discord.com", "discordapp.com", "discordapp.net", "discord.gg", "discord.media", "discord.co")
        ),
        SiteEntry(
            id = "reddit",
            name = "Reddit",
            url = "reddit.com",
            category = "社区",
            aliases = listOf("reddit", "红迪"),
            domains = listOf("reddit.com", "redd.it", "redditstatic.com", "redditmedia.com")
        ),
        SiteEntry(
            id = "instagram",
            name = "Instagram",
            url = "instagram.com",
            category = "社交",
            aliases = listOf("ins", "ig", "instagram"),
            domains = listOf("instagram.com", "cdninstagram.com", "ig.me")
        ),
        SiteEntry(
            id = "whatsapp",
            name = "WhatsApp",
            url = "whatsapp.com",
            category = "社交",
            aliases = listOf("wa", "whatsapp"),
            domains = listOf("whatsapp.com", "whatsapp.net", "wa.me")
        ),
        SiteEntry(
            id = "facebook-meta",
            name = "Meta / Facebook",
            url = "facebook.com",
            category = "社交",
            aliases = listOf("fb", "meta", "facebook", "threads"),
            domains = listOf("facebook.com", "fbcdn.net", "fb.com", "fbsbx.com", "messenger.com", "meta.com", "threads.net")
        ),
        SiteEntry(
            id = "tiktok",
            name = "TikTok",
            url = "tiktok.com",
            category = "视频",
            aliases = listOf("tiktok", "海外抖音"),
            domains = listOf("tiktok.com", "tiktokcdn.com", "tiktokcdn-us.com", "tiktokv.com", "musical.ly")
        ),
        SiteEntry(
            id = "twitch",
            name = "Twitch",
            url = "twitch.tv",
            category = "直播",
            aliases = listOf("twitch"),
            domains = listOf("twitch.tv", "ttvnw.net", "jtvnw.net", "ext-twitch.tv")
        ),
        SiteEntry(
            id = "netflix",
            name = "Netflix",
            url = "netflix.com",
            category = "视频",
            aliases = listOf("nf", "奈飞", "网飞", "netflix"),
            domains = listOf("netflix.com", "nflxvideo.net", "nflximg.net", "nflximg.com", "nflxso.net", "nflxext.com")
        ),
        SiteEntry(
            id = "spotify",
            name = "Spotify",
            url = "spotify.com",
            category = "音乐",
            aliases = listOf("spotify", "声田"),
            domains = listOf("spotify.com", "scdn.co", "spotifycdn.com", "spoti.fi")
        ),
        SiteEntry(
            id = "disney",
            name = "Disney+",
            url = "disneyplus.com",
            category = "视频",
            aliases = listOf("disney", "迪士尼"),
            domains = listOf("disneyplus.com", "disney-plus.net", "bamgrid.com", "dssott.com")
        ),
        SiteEntry(
            id = "steam",
            name = "Steam",
            url = "store.steampowered.com",
            category = "游戏",
            aliases = listOf("steam", "valve", "v社"),
            domains = listOf("steampowered.com", "steamcommunity.com", "steamstatic.com", "steamcontent.com", "steamserver.net", "steamgames.com", "valvesoftware.com")
        ),
        SiteEntry(
            id = "epic-games",
            name = "Epic Games",
            url = "epicgames.com",
            category = "游戏",
            aliases = listOf("epic", "unreal"),
            domains = listOf("epicgames.com", "epicgames.dev", "unrealengine.com")
        ),
        SiteEntry(
            id = "playstation",
            name = "PlayStation",
            url = "playstation.com",
            category = "游戏",
            aliases = listOf("psn", "ps5", "sony", "索尼"),
            domains = listOf("playstation.com", "playstation.net", "sonyentertainmentnetwork.com")
        ),
        SiteEntry(
            id = "xbox",
            name = "Xbox",
            url = "xbox.com",
            category = "游戏",
            aliases = listOf("xbox"),
            domains = listOf("xbox.com", "xboxservices.com")
        ),
        SiteEntry(
            id = "nintendo",
            name = "任天堂 / Nintendo",
            url = "nintendo.com",
            category = "游戏",
            aliases = listOf("switch", "nintendo", "任天堂"),
            domains = listOf("nintendo.com", "nintendo.net", "nintendo.co.jp")
        ),
        SiteEntry(
            id = "riot",
            name = "拳头游戏 / Riot",
            url = "riotgames.com",
            category = "游戏",
            aliases = listOf("riot", "lol", "valorant", "瓦罗兰特"),
            domains = listOf("riotgames.com", "riotcdn.net", "leagueoflegends.com", "playvalorant.com")
        ),
        SiteEntry(
            id = "roblox",
            name = "Roblox",
            url = "roblox.com",
            category = "游戏",
            aliases = listOf("roblox"),
            domains = listOf("roblox.com", "rbxcdn.com")
        ),
        SiteEntry(
            id = "notion",
            name = "Notion",
            url = "notion.so",
            category = "办公",
            aliases = listOf("notion"),
            domains = listOf("notion.so", "notion.site", "notion-static.com")
        ),
        SiteEntry(
            id = "figma",
            name = "Figma",
            url = "figma.com",
            category = "设计",
            aliases = listOf("figma"),
            domains = listOf("figma.com", "figma-alpha.com")
        ),
        SiteEntry(
            id = "slack",
            name = "Slack",
            url = "slack.com",
            category = "办公",
            aliases = listOf("slack"),
            domains = listOf("slack.com", "slack-edge.com", "slack-imgs.com")
        ),
        SiteEntry(
            id = "zoom",
            name = "Zoom",
            url = "zoom.us",
            category = "办公",
            aliases = listOf("zoom"),
            domains = listOf("zoom.us", "zoom.com", "zoomgov.com")
        ),
        SiteEntry(
            id = "dropbox",
            name = "Dropbox",
            url = "dropbox.com",
            category = "云盘",
            aliases = listOf("dropbox"),
            domains = listOf("dropbox.com", "dropboxstatic.com", "dropboxusercontent.com")
        ),
        SiteEntry(
            id = "linkedin",
            name = "LinkedIn",
            url = "linkedin.com",
            category = "职场",
            aliases = listOf("linkedin", "领英"),
            domains = listOf("linkedin.com", "licdn.com")
        ),
        SiteEntry(
            id = "wikipedia",
            name = "维基百科",
            url = "wikipedia.org",
            category = "百科",
            aliases = listOf("wiki", "wikipedia", "维基"),
            domains = listOf("wikipedia.org", "wikimedia.org", "wiktionary.org", "wikidata.org")
        ),
        SiteEntry(
            id = "amazon",
            name = "Amazon",
            url = "amazon.com",
            category = "购物",
            aliases = listOf("amazon", "亚马逊"),
            domains = listOf("amazon.com", "amazon.co.jp", "amazon.co.uk", "amazon.cn", "media-amazon.com", "images-amazon.com", "ssl-images-amazon.com", "a2z.com")
        ),
        SiteEntry(
            id = "aws",
            name = "Amazon AWS",
            url = "aws.amazon.com",
            category = "云服务",
            aliases = listOf("aws", "cloudfront", "s3"),
            domains = listOf("amazonaws.com", "aws.amazon.com", "cloudfront.net", "awsstatic.com", "aws.dev")
        ),
        SiteEntry(
            id = "cloudflare",
            name = "Cloudflare",
            url = "cloudflare.com",
            category = "CDN / 基础设施",
            aliases = listOf("cf", "cloudflare", "warp"),
            domains = listOf("cloudflare.com", "cloudflare.net", "cloudflare-dns.com", "cloudflareinsights.com", "cloudflareclient.com", "workers.dev", "pages.dev", "r2.dev", "cf-ipfs.com", "one.one.one.one")
        ),
        SiteEntry(
            id = "akamai",
            name = "Akamai CDN",
            url = "akamai.com",
            category = "CDN / 基础设施",
            aliases = listOf("akamai"),
            domains = listOf("akamai.net", "akamaiedge.net", "akamaihd.net", "akamaized.net", "akadns.net", "edgekey.net", "edgesuite.net", "akamaitechnologies.com")
        ),
        SiteEntry(
            id = "fastly",
            name = "Fastly CDN",
            url = "fastly.com",
            category = "CDN / 基础设施",
            aliases = listOf("fastly"),
            domains = listOf("fastly.net", "fastlylb.net", "fastly.com")
        ),
        SiteEntry(
            id = "vercel-netlify",
            name = "Vercel / Netlify",
            url = "vercel.com",
            category = "云服务",
            aliases = listOf("vercel", "netlify", "supabase"),
            domains = listOf("vercel.com", "vercel.app", "vercel-insights.com", "netlify.com", "netlify.app", "supabase.co", "supabase.com")
        ),
        SiteEntry(
            id = "nvidia",
            name = "NVIDIA",
            url = "nvidia.com",
            category = "硬件",
            aliases = listOf("nv", "nvidia", "英伟达", "geforce"),
            domains = listOf("nvidia.com", "nvidia.cn", "nvgs.nvidia.com", "geforce.com")
        ),
        SiteEntry(
            id = "mozilla",
            name = "Mozilla / Firefox",
            url = "mozilla.org",
            category = "浏览器",
            aliases = listOf("firefox", "mozilla", "火狐"),
            domains = listOf("mozilla.org", "mozilla.com", "mozilla.net", "firefox.com")
        ),
        SiteEntry(
            id = "paypal-stripe",
            name = "PayPal / Stripe",
            url = "paypal.com",
            category = "支付",
            aliases = listOf("paypal", "stripe"),
            domains = listOf("paypal.com", "paypalobjects.com", "stripe.com", "stripe.network")
        ),
        SiteEntry(
            id = "sentry-telemetry",
            name = "Sentry / 监控遥测",
            url = "sentry.io",
            category = "监控",
            aliases = listOf("sentry", "datadog", "telemetry"),
            domains = listOf("sentry.io", "datadoghq.com", "segment.io", "mixpanel.com", "amplitude.com")
        ),
        SiteEntry(
            id = "ntp-time",
            name = "时间同步 (NTP)",
            url = "pool.ntp.org",
            category = "基础设施",
            aliases = listOf("ntp", "time"),
            domains = listOf("pool.ntp.org", "time.windows.com", "time.apple.com", "time.google.com", "time.cloudflare.com", "ntp.aliyun.com", "ntp.tencent.com")
        ),
        SiteEntry(
            id = "public-dns-cf",
            name = "Cloudflare DNS",
            url = "1.1.1.1",
            category = "公共 DNS",
            aliases = listOf("cf dns", "1.1.1.1"),
            domains = emptyList(),
            ips = listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
        ),
        SiteEntry(
            id = "public-dns-google",
            name = "Google DNS",
            url = "8.8.8.8",
            category = "公共 DNS",
            aliases = listOf("google dns", "8.8.8.8"),
            domains = emptyList(),
            ips = listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")
        ),
        SiteEntry(
            id = "public-dns-ali",
            name = "阿里公共 DNS",
            url = "alidns.com",
            category = "公共 DNS",
            aliases = listOf("alidns", "223.5.5.5"),
            domains = emptyList(),
            ips = listOf("223.5.5.5", "223.6.6.6")
        ),
        SiteEntry(
            id = "public-dns-dnspod",
            name = "腾讯 DNSPod",
            url = "dnspod.cn",
            category = "公共 DNS",
            aliases = listOf("dnspod", "119.29.29.29"),
            domains = emptyList(),
            ips = listOf("119.29.29.29", "119.28.28.28")
        ),
        SiteEntry(
            id = "public-dns-114",
            name = "114 DNS",
            url = "114dns.com",
            category = "公共 DNS",
            aliases = listOf("114dns", "114.114.114.114"),
            domains = emptyList(),
            ips = listOf("114.114.114.114", "114.114.115.115")
        ),
        SiteEntry(
            id = "public-dns-quad9",
            name = "Quad9 DNS",
            url = "quad9.net",
            category = "公共 DNS",
            aliases = listOf("quad9", "9.9.9.9"),
            domains = listOf("quad9.net"),
            ips = listOf("9.9.9.9", "149.112.112.112")
        )
    )

    private val ipv4Regex = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    private val domainIndex: Map<String, SiteMatch> = buildMap {
        for (entry in catalog) {
            for (raw in entry.domains) {
                val domain = raw.lowercase(Locale.ROOT).trimEnd('.')
                if (domain.isNotEmpty() && !containsKey(domain)) {
                    put(
                        domain,
                        SiteMatch(
                            id = entry.id,
                            name = entry.name,
                            url = entry.url,
                            category = entry.category,
                            matchedDomain = domain,
                            aliases = entry.aliases
                        )
                    )
                }
            }
        }
    }

    private val ipIndex: Map<String, SiteMatch> = buildMap {
        for (entry in catalog) {
            for (raw in entry.ips) {
                val ip = raw.lowercase(Locale.ROOT)
                if (ip.isNotEmpty() && !containsKey(ip)) {
                    put(
                        ip,
                        SiteMatch(
                            id = entry.id,
                            name = entry.name,
                            url = entry.url,
                            category = entry.category,
                            matchedDomain = ip,
                            aliases = entry.aliases
                        )
                    )
                }
            }
        }
    }

    val domainCount: Int get() = domainIndex.size

    fun normalizeHost(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var host = raw.trim().lowercase(Locale.ROOT).trimEnd('.')
        if (host.startsWith('[') && host.endsWith(']') && host.length > 2) {
            host = host.substring(1, host.length - 1)
        }
        return host
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.isEmpty()) return false
        if (host.contains(':')) return true
        return ipv4Regex.matches(host)
    }

    fun resolveSite(rawHost: String?): SiteMatch? {
        val host = normalizeHost(rawHost)
        if (host.isEmpty() || host == "未知目标" || host == "未知") return null
        if (isIpLiteral(host)) {
            return ipIndex[host]
        }
        // 沿点边界从最具体子域名逐级向上查表，确保最长后缀优先并防止部分串误命中。
        // Walk dot boundaries from most-specific to broadest suffix so longest suffix wins without partial hits.
        var cursor = host
        while (cursor.isNotEmpty()) {
            domainIndex[cursor]?.let { return it }
            val dot = cursor.indexOf('.')
            if (dot < 0) break
            cursor = cursor.substring(dot + 1)
        }
        return null
    }
}
