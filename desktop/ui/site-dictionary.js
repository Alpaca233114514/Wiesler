(function(root) {
  'use strict';

  // 离线「域名 / 公共 DNS IP → 网站或服务」字典；仅用于界面可读展示，不篡改原始抓包证据。
  // Offline domain/public-DNS-IP to site/service dictionary for readable UI display; raw host evidence is preserved.
  const ENTRIES = [
    // --- 国内主流视频 / 内容 / 社交 ---
    {
      id: 'bilibili',
      name: '哔哩哔哩',
      url: 'bilibili.com',
      category: '视频',
      aliases: ['b站', 'bili', 'bilibili', '小破站'],
      domains: [
        'bilibili.com', 'bilibili.tv', 'bilivideo.com', 'bilivideo.cn', 'bilivideo.net',
        'hdslb.com', 'biliapi.com', 'biliapi.net', 'biligame.com', 'biligame.net',
        'b23.tv', 'acg.tv', 'acgvideo.com', 'bilicomic.com', 'bilicomics.com', 'im9.com'
      ]
    },
    {
      id: 'wechat',
      name: '微信',
      url: 'weixin.qq.com',
      category: '社交',
      aliases: ['wx', 'wechat', 'weixin', '公众号', '朋友圈'],
      domains: [
        'weixin.qq.com', 'weixin.qq.com.cn', 'wechat.com', 'servicewechat.com',
        'wx.qq.com', 'wx.qlogo.cn', 'wx.gtimg.com', 'mmbiz.qpic.cn', 'mmbiz.qlogo.cn',
        'mmsns.qpic.cn', 'res.wx.qq.com', 'mp.weixin.qq.com', 'channels.weixin.qq.com',
        'finder.video.qq.com', 'weixinbridge.com', 'tenpay.com'
      ]
    },
    {
      id: 'wecom',
      name: '企业微信',
      url: 'work.weixin.qq.com',
      category: '办公',
      aliases: ['企微', 'wecom', 'wxwork'],
      domains: ['work.weixin.qq.com', 'wework.qpic.cn', 'wwcdn.weixin.qq.com']
    },
    {
      id: 'tencent-video',
      name: '腾讯视频',
      url: 'v.qq.com',
      category: '视频',
      aliases: ['wetv', 'tencent video'],
      domains: ['v.qq.com', 'video.qq.com', 'wetv.vip', 'vv.video.qq.com', 'vm.gtimg.cn']
    },
    {
      id: 'qq-music',
      name: 'QQ 音乐',
      url: 'y.qq.com',
      category: '音乐',
      aliases: ['qqmusic'],
      domains: ['y.qq.com', 'music.qq.com', 'aqqmusic.tc.qq.com', 'y.gtimg.cn']
    },
    {
      id: 'tencent-cloud',
      name: '腾讯云',
      url: 'cloud.tencent.com',
      category: '云服务',
      aliases: ['qcloud', 'dnspod'],
      domains: ['cloud.tencent.com', 'qcloud.com', 'myqcloud.com', 'tencent-cloud.net', 'tencentcloudapi.com', 'tencentyun.com', 'dnspod.cn', 'dnspod.net', 'doh.pub']
    },
    {
      id: 'qq-tencent',
      name: '腾讯 / QQ',
      url: 'qq.com',
      category: '社交',
      aliases: ['tencent', 'qq', '鹅厂'],
      domains: ['qq.com', 'tencent.com', 'gtimg.cn', 'gtimg.com', 'qpic.cn', 'qlogo.cn', 'idqqimg.com', 'myapp.com', 'qqmail.com']
    },
    {
      id: 'douyin',
      name: '抖音',
      url: 'douyin.com',
      category: '视频',
      aliases: ['dy', 'douyin'],
      domains: [
        'douyin.com', 'douyincdn.com', 'douyinpic.com', 'douyinvod.com', 'douyinliving.com',
        'douyinstatic.com', 'iesdouyin.com', 'amemv.com', 'aweme.snssdk.com'
      ]
    },
    {
      id: 'feishu',
      name: '飞书',
      url: 'feishu.cn',
      category: '办公',
      aliases: ['lark', 'feishu'],
      domains: ['feishu.cn', 'feishu.net', 'feishucdn.com', 'larksuite.com', 'larkoffice.com']
    },
    {
      id: 'doubao',
      name: '豆包 AI',
      url: 'doubao.com',
      category: 'AI',
      aliases: ['doubao', 'coze', '扣子'],
      domains: ['doubao.com', 'coze.cn', 'coze.com', 'volces.com']
    },
    {
      id: 'bytedance',
      name: '字节跳动 / 头条',
      url: 'toutiao.com',
      category: '资讯',
      aliases: ['byte', 'bytedance', '今日头条', '西瓜视频'],
      domains: [
        'bytedance.com', 'bytedance.net', 'byteimg.com', 'bytetos.com', 'bytedns.net',
        'bytecdn.cn', 'bytegoofy.com', 'byteoversea.com', 'ibytedtos.com', 'snssdk.com',
        'toutiao.com', 'toutiaocdn.com', 'toutiaopic.com', 'ixigua.com', 'pstatp.com',
        'zjcdn.com', 'volcengine.com', 'volccdn.com'
      ]
    },
    {
      id: 'xiaohongshu',
      name: '小红书',
      url: 'xiaohongshu.com',
      category: '社交',
      aliases: ['xhs', 'rednote', 'redbook'],
      domains: ['xiaohongshu.com', 'xhscdn.com', 'xhscdn.net', 'xhslink.com', 'fengkongcloud.com']
    },
    {
      id: 'zhihu',
      name: '知乎',
      url: 'zhihu.com',
      category: '社区',
      aliases: ['zhihu'],
      domains: ['zhihu.com', 'zhimg.com']
    },
    {
      id: 'weibo',
      name: '微博',
      url: 'weibo.com',
      category: '社交',
      aliases: ['sina', 'weibo', '新浪'],
      domains: ['weibo.com', 'weibo.cn', 'weibocdn.com', 'sinaimg.cn', 'sinajs.cn', 'sina.com.cn']
    },
    {
      id: 'douban',
      name: '豆瓣',
      url: 'douban.com',
      category: '社区',
      aliases: ['douban'],
      domains: ['douban.com', 'doubanio.com']
    },
    {
      id: 'kuaishou',
      name: '快手',
      url: 'kuaishou.com',
      category: '视频',
      aliases: ['ks', 'kuaishou'],
      domains: ['kuaishou.com', 'kwai.com', 'kwaicdn.com', 'yximgs.com', 'gifshow.com', 'ksapisrv.com']
    },
    {
      id: 'iqiyi',
      name: '爱奇艺',
      url: 'iqiyi.com',
      category: '视频',
      aliases: ['iqiyi', 'qiyi'],
      domains: ['iqiyi.com', 'qiyi.com', 'iqiyipic.com', 'qy.net', 'iq.com']
    },
    {
      id: 'youku',
      name: '优酷',
      url: 'youku.com',
      category: '视频',
      aliases: ['youku'],
      domains: ['youku.com', 'ykimg.com', 'tudou.com', 'soku.com']
    },
    {
      id: 'mangotv',
      name: '芒果 TV',
      url: 'mgtv.com',
      category: '视频',
      aliases: ['mgtv'],
      domains: ['mgtv.com', 'hunantv.com', 'hitv.com']
    },
    {
      id: 'netease-music',
      name: '网易云音乐',
      url: 'music.163.com',
      category: '音乐',
      aliases: ['云村', '网易云', '163music'],
      domains: ['music.163.com', 'music.126.net']
    },
    {
      id: 'netease',
      name: '网易',
      url: '163.com',
      category: '资讯',
      aliases: ['netease', '163'],
      domains: ['163.com', '126.com', '126.net', 'netease.com', 'netease.im', 'ydstatic.com', 'youdao.com', 'lofter.com']
    },
    {
      id: 'taobao',
      name: '淘宝 / 天猫',
      url: 'taobao.com',
      category: '购物',
      aliases: ['tb', 'tmall', 'taobao', '闲鱼', '淘系'],
      domains: ['taobao.com', 'tmall.com', 'tbcdn.cn', 'taobaocdn.com', 'goofish.com', 'etao.com', 'mmstat.com', 'tanx.com', 'cainiao.com']
    },
    {
      id: 'alipay',
      name: '支付宝',
      url: 'alipay.com',
      category: '支付',
      aliases: ['zfb', 'alipay', '蚂蚁'],
      domains: ['alipay.com', 'alipayobjects.com', 'alipaydns.com', 'antgroup.com', 'antfin.com']
    },
    {
      id: 'dingtalk',
      name: '钉钉',
      url: 'dingtalk.com',
      category: '办公',
      aliases: ['dingtalk', '钉钉'],
      domains: ['dingtalk.com', 'dingtalkapps.com']
    },
    {
      id: 'qwen',
      name: '通义千问',
      url: 'tongyi.aliyun.com',
      category: 'AI',
      aliases: ['qwen', 'tongyi', '千问'],
      domains: ['qwen.ai', 'qwenlm.ai', 'tongyi.aliyun.com', 'dashscope.aliyuncs.com']
    },
    {
      id: 'aliyun',
      name: '阿里云 / 阿里',
      url: 'aliyun.com',
      category: '云服务',
      aliases: ['alibaba', 'aliyun', 'alidns'],
      domains: ['aliyun.com', 'aliyuncs.com', 'alicdn.com', 'alidns.com', 'alibaba.com', 'alibabagroup.com', 'alibabacloud.com', '1688.com', 'aliyundrive.com', 'alipan.com']
    },
    {
      id: 'amap',
      name: '高德地图',
      url: 'amap.com',
      category: '地图',
      aliases: ['gaode', 'amap'],
      domains: ['amap.com', 'autonavi.com']
    },
    {
      id: 'hellobike',
      name: '哈啰出行',
      url: 'hellobike.com',
      category: '出行',
      aliases: ['哈啰', 'hellobike', '单车'],
      domains: ['hellobike.com', 'hello-inc.com']
    },
    {
      id: 'didi',
      name: '滴滴出行',
      url: 'didiglobal.com',
      category: '出行',
      aliases: ['滴滴', 'didi'],
      domains: ['didiglobal.com', 'didichuxing.com', 'udache.com', 'xiaojukeji.com']
    },
    {
      id: 'ctrip',
      name: '携程',
      url: 'ctrip.com',
      category: '出行',
      aliases: ['携程', 'ctrip', 'trip'],
      domains: ['ctrip.com', 'c-ctrip.com', 'trip.com']
    },
    {
      id: 'railway-12306',
      name: '铁路 12306',
      url: '12306.cn',
      category: '出行',
      aliases: ['12306', '火车票'],
      domains: ['12306.cn']
    },
    {
      id: 'eleme',
      name: '饿了么',
      url: 'ele.me',
      category: '生活',
      aliases: ['饿了么', 'eleme'],
      domains: ['ele.me', 'elemecdn.com']
    },
    {
      id: 'china-telecom',
      name: '中国电信',
      url: '189.cn',
      category: '运营商',
      aliases: ['电信', 'telecom', 'rcs', '189'],
      domains: ['189.cn', 'ct10000.com', 'chinatelecom.com.cn']
    },
    {
      id: 'china-mobile',
      name: '中国移动',
      url: '10086.cn',
      category: '运营商',
      aliases: ['移动', 'cmcc', '10086'],
      domains: ['10086.cn', 'chinamobile.com', 'cmvideo.cn']
    },
    {
      id: 'china-unicom',
      name: '中国联通',
      url: '10010.com',
      category: '运营商',
      aliases: ['联通', 'unicom', '10010'],
      domains: ['10010.com', 'chinaunicom.com']
    },
    {
      id: 'push-analytics-sdk',
      name: '推送 / 统计 SDK',
      url: 'getui.com',
      category: 'SDK',
      aliases: ['个推', '极光', '友盟', 'getui', 'jpush', 'umeng'],
      domains: ['getui.com', 'getui.net', 'igexin.com', 'jpush.cn', 'jiguang.cn', 'umeng.com', 'umengcloud.com', 'umsns.com', 'bugly.qq.com']
    },
    {
      id: 'egress-probe',
      name: '公网出口 / 归属地探测',
      url: 'ipify.org',
      category: '探测',
      aliases: ['ip', 'egress', 'ipify', 'ipwho', 'wiesler'],
      domains: ['ipify.org', 'ipwho.is', 'ipinfo.io', 'ip.sb', 'ifconfig.me']
    },
    {
      id: 'jd',
      name: '京东',
      url: 'jd.com',
      category: '购物',
      aliases: ['jd', 'jingdong'],
      domains: ['jd.com', '360buyimg.com', '360buy.com', 'jdcloud.com', 'jcloudcs.com', 'jdpay.com', 'jdl.com']
    },
    {
      id: 'pinduoduo',
      name: '拼多多',
      url: 'pinduoduo.com',
      category: '购物',
      aliases: ['pdd', 'temu'],
      domains: ['pinduoduo.com', 'yangkeduo.com', 'pddpic.com', 'temu.com']
    },
    {
      id: 'meituan',
      name: '美团 / 点评',
      url: 'meituan.com',
      category: '生活',
      aliases: ['meituan', '大众点评'],
      domains: ['meituan.com', 'meituan.net', 'dianping.com', 'dpfile.com', 'sankuai.com']
    },
    {
      id: 'baidu',
      name: '百度',
      url: 'baidu.com',
      category: '搜索',
      aliases: ['baidu', '百度网盘', '文心'],
      domains: ['baidu.com', 'bdstatic.com', 'bdimg.com', 'bcebos.com', 'baidupcs.com', 'baidubce.com', 'baidustatic.com', 'hao123.com']
    },
    {
      id: 'deepseek',
      name: 'DeepSeek',
      url: 'chat.deepseek.com',
      category: 'AI',
      aliases: ['deepseek', '深度求索'],
      domains: ['deepseek.com']
    },
    {
      id: 'kimi',
      name: 'Kimi',
      url: 'kimi.moonshot.cn',
      category: 'AI',
      aliases: ['kimi', 'moonshot', '月之暗面'],
      domains: ['moonshot.cn', 'kimi.ai', 'kimi.com']
    },
    {
      id: 'wps',
      name: 'WPS 办公',
      url: 'wps.cn',
      category: '办公',
      aliases: ['wps', '金山'],
      domains: ['wps.cn', 'wps.com', 'kdocs.cn', 'ksyun.com', 'kingsoft.com']
    },
    {
      id: 'mihoyo',
      name: '米哈游',
      url: 'mihoyo.com',
      category: '游戏',
      aliases: ['mihoyo', 'hoyoverse', '原神', '崩坏'],
      domains: ['mihoyo.com', 'hoyoverse.com', 'hoyolab.com', 'yuanshen.com', 'bhsr.com']
    },
    {
      id: 'xiaomi',
      name: '小米',
      url: 'mi.com',
      category: '系统',
      aliases: ['mi', 'xiaomi', 'miui', 'hyperos'],
      domains: ['mi.com', 'xiaomi.com', 'xiaomi.net', 'miui.com', 'mifile.cn', 'mi-img.com']
    },
    {
      id: 'huawei',
      name: '华为',
      url: 'huawei.com',
      category: '系统',
      aliases: ['huawei', 'harmony', '鸿蒙'],
      domains: ['huawei.com', 'huaweicloud.com', 'hicloud.com', 'vmall.com', 'dbankcdn.com', 'honor.com', 'hihonor.com']
    },
    {
      id: 'oppo-vivo',
      name: 'OPPO / vivo',
      url: 'oppo.com',
      category: '系统',
      aliases: ['oppo', 'vivo', 'oneplus', '一加', 'realme'],
      domains: ['oppo.com', 'oppomobile.com', 'heytap.com', 'heytapdl.com', 'heytapimage.com', 'oneplus.com', 'vivo.com', 'vivo.com.cn']
    },
    {
      id: 'juejin-csdn',
      name: '国内技术社区',
      url: 'juejin.cn',
      category: '开发',
      aliases: ['掘金', 'csdn', 'gitee', '码云', '博客园'],
      domains: ['juejin.cn', 'csdn.net', 'csdnimg.cn', 'gitee.com', 'cnblogs.com', 'oschina.net', 'v2ex.com']
    },

    // --- 国际主流 AI / 开发 / 科技 ---
    {
      id: 'youtube',
      name: 'YouTube',
      url: 'youtube.com',
      category: '视频',
      aliases: ['yt', 'ytb', '油管', 'youtube'],
      domains: [
        'youtube.com', 'youtu.be', 'googlevideo.com', 'ytimg.com', 'ggpht.com',
        'youtube-nocookie.com', 'youtubei.googleapis.com', 'yt3.googleusercontent.com', 'yt4.ggpht.com'
      ]
    },
    {
      id: 'google-ai',
      name: 'Google AI / Gemini',
      url: 'gemini.google.com',
      category: 'AI',
      aliases: ['gemini', 'aistudio', 'google ai', 'antigravity'],
      domains: [
        'gemini.google.com', 'aistudio.google.com', 'deepmind.google', 'deepmind.com',
        'generativelanguage.googleapis.com', 'aiplatform.googleapis.com',
        'aicode.googleapis.com', 'cloudcode-pa.googleapis.com', 'daily-cloudcode-pa.googleapis.com',
        'notebooklm.google.com', 'labs.google'
      ]
    },
    {
      id: 'gmail',
      name: 'Gmail',
      url: 'mail.google.com',
      category: '办公',
      aliases: ['gmail', '谷歌邮箱'],
      domains: ['mail.google.com', 'gmail.com', 'googlemail.com']
    },
    {
      id: 'google-play',
      name: 'Google Play',
      url: 'play.google.com',
      category: '应用商店',
      aliases: ['play', 'google play', '谷歌商店'],
      domains: ['play.google.com', 'play.googleapis.com', 'play-fe.googleapis.com', 'android.clients.google.com', 'gvt1.com', 'gvt2.com', 'gvt3.com']
    },
    {
      id: 'google-drive',
      name: 'Google 云端硬盘 / 文档',
      url: 'drive.google.com',
      category: '办公',
      aliases: ['gdrive', 'google docs', 'google drive'],
      domains: ['drive.google.com', 'docs.google.com', 'sheets.google.com', 'slides.google.com', 'photos.google.com']
    },
    {
      id: 'google',
      name: 'Google',
      url: 'google.com',
      category: '搜索',
      aliases: ['google', '谷歌', 'chrome', 'gstatic', 'android'],
      domains: [
        'google.com', 'google.com.hk', 'google.co.jp', 'google.cn', 'googleapis.com',
        'gstatic.com', 'googleusercontent.com', 'googleadservices.com', 'googlesyndication.com',
        'googletagmanager.com', 'google-analytics.com', 'doubleclick.net', '1e100.net',
        'g.co', 'goo.gl', 'android.com', 'chromium.org', 'firebaseio.com', 'firebase.google.com',
        'withgoogle.com', 'pki.goog', 'dns.google'
      ]
    },
    {
      id: 'openai',
      name: 'OpenAI / ChatGPT',
      url: 'chatgpt.com',
      category: 'AI',
      aliases: ['openai', 'chatgpt', 'gpt', 'codex'],
      domains: ['openai.com', 'chatgpt.com', 'oaistatic.com', 'oaiusercontent.com', 'sora.com']
    },
    {
      id: 'claude',
      name: 'Claude / Anthropic',
      url: 'claude.ai',
      category: 'AI',
      aliases: ['claude', 'anthropic'],
      domains: ['claude.ai', 'anthropic.com', 'claude.com']
    },
    {
      id: 'cursor',
      name: 'Cursor AI',
      url: 'cursor.com',
      category: 'AI',
      aliases: ['cursor'],
      domains: ['cursor.com', 'cursor.sh', 'cursor-cdn.com']
    },
    {
      id: 'xai',
      name: 'xAI / Grok',
      url: 'grok.com',
      category: 'AI',
      aliases: ['grok', 'xai'],
      domains: ['x.ai', 'grok.com']
    },
    {
      id: 'perplexity',
      name: 'Perplexity',
      url: 'perplexity.ai',
      category: 'AI',
      aliases: ['perplexity', 'pplx'],
      domains: ['perplexity.ai', 'pplx.ai']
    },
    {
      id: 'huggingface',
      name: 'Hugging Face',
      url: 'huggingface.co',
      category: 'AI',
      aliases: ['hf', 'huggingface', '抱抱脸'],
      domains: ['huggingface.co', 'hf.co', 'hf.space']
    },
    {
      id: 'github',
      name: 'GitHub',
      url: 'github.com',
      category: '开发',
      aliases: ['gh', 'github', 'copilot'],
      domains: [
        'github.com', 'githubusercontent.com', 'githubassets.com', 'github.io',
        'github.dev', 'ghcr.io', 'githubcopilot.com', 'githubstatus.com', 'git.io'
      ]
    },
    {
      id: 'gitlab',
      name: 'GitLab',
      url: 'gitlab.com',
      category: '开发',
      aliases: ['gitlab'],
      domains: ['gitlab.com', 'gitlab.io', 'gitlab-static.net']
    },
    {
      id: 'stackoverflow',
      name: 'Stack Overflow',
      url: 'stackoverflow.com',
      category: '开发',
      aliases: ['so', 'stackoverflow'],
      domains: ['stackoverflow.com', 'stackexchange.com', 'sstatic.net']
    },
    {
      id: 'dev-registries',
      name: '开源包仓库 / 镜像',
      url: 'npmjs.org',
      category: '开发',
      aliases: ['npm', 'pypi', 'docker', 'crates', 'rust', 'maven', 'gradle', 'brew'],
      domains: [
        'npmjs.org', 'npmjs.com', 'pypi.org', 'pythonhosted.org', 'docker.com', 'docker.io',
        'crates.io', 'rust-lang.org', 'rustup.rs', 'maven.org', 'gradle.org', 'brew.sh',
        'packagist.org', 'rubygems.org', 'golang.org', 'go.dev', 'nuget.org', 'jsdelivr.net', 'unpkg.com', 'cdnjs.cloudflare.com'
      ]
    },
    {
      id: 'jetbrains',
      name: 'JetBrains',
      url: 'jetbrains.com',
      category: '开发',
      aliases: ['idea', 'pycharm', 'jetbrains'],
      domains: ['jetbrains.com', 'jetbrains.net', 'intellij.net']
    },
    {
      id: 'ms-edge',
      name: '微软 Edge',
      url: 'microsoft.com/edge',
      category: '浏览器',
      aliases: ['edge', 'msedge'],
      domains: ['edge.microsoft.com', 'msedge.net', 't-msedge.net', 'a-msedge.net', 's-msedge.net', 'b-msedge.net', 'l-msedge.net', 'edge.activity.windows.com']
    },
    {
      id: 'windows-update',
      name: 'Windows 系统服务',
      url: 'windows.com',
      category: '系统',
      aliases: ['windows', 'winupdate', 'msftconnecttest'],
      domains: [
        'windowsupdate.com', 'update.microsoft.com', 'windows.com', 'windows.net',
        'msftconnecttest.com', 'msftncsi.com', 'delivery.mp.microsoft.com', 'settings-win.data.microsoft.com'
      ]
    },
    {
      id: 'office-onedrive',
      name: 'Microsoft 365 / OneDrive',
      url: 'office.com',
      category: '办公',
      aliases: ['office', 'onedrive', 'outlook', 'teams', 'sharepoint'],
      domains: ['office.com', 'office.net', 'office365.com', 'onedrive.com', 'onedrive.live.com', 'sharepoint.com', 'outlook.com', 'outlook.office365.com', 'teams.microsoft.com', 'live.com', '1drv.ms']
    },
    {
      id: 'microsoft',
      name: '微软 / Bing',
      url: 'microsoft.com',
      category: '科技',
      aliases: ['ms', 'microsoft', 'bing', 'azure', 'vscode', '必应'],
      domains: [
        'microsoft.com', 'microsoftonline.com', 'msn.com', 'bing.com', 'bing.net',
        'azure.com', 'azureedge.net', 'azurewebsites.net', 'msecnd.net', 'msocdn.com',
        'visualstudio.com', 'vsassets.io', 'xboxlive.com', 'sfx.ms', 'aka.ms'
      ]
    },
    {
      id: 'icloud',
      name: 'iCloud',
      url: 'icloud.com',
      category: '云盘',
      aliases: ['icloud', '苹果云'],
      domains: ['icloud.com', 'icloud.com.cn', 'icloud-content.com', 'me.com', 'mac.com']
    },
    {
      id: 'apple-media',
      name: 'App Store / Apple 媒体',
      url: 'apps.apple.com',
      category: '应用商店',
      aliases: ['appstore', 'itunes', 'apple music'],
      domains: ['itunes.apple.com', 'apps.apple.com', 'mzstatic.com', 'aaplimg.com', 'music.apple.com', 'tv.apple.com']
    },
    {
      id: 'apple',
      name: '苹果服务',
      url: 'apple.com',
      category: '系统',
      aliases: ['apple', 'ios', 'macos', '苹果'],
      domains: ['apple.com', 'apple.com.cn', 'apple-dns.net', 'cdn-apple.com', 'apple-cloudkit.com', 'push.apple.com', 'ls-apple.com.akadns.net']
    },
    {
      id: 'x-twitter',
      name: 'X (Twitter)',
      url: 'x.com',
      category: '社交',
      aliases: ['twitter', 'x', '推特'],
      domains: ['x.com', 'twitter.com', 'twimg.com', 't.co', 'tweetdeck.com']
    },
    {
      id: 'telegram',
      name: 'Telegram',
      url: 'telegram.org',
      category: '社交',
      aliases: ['tg', '电报', 'telegram'],
      domains: ['telegram.org', 'telegram.me', 't.me', 'tdesktop.com', 'telegra.ph', 'telesco.pe', 'cdn-telegram.org']
    },
    {
      id: 'discord',
      name: 'Discord',
      url: 'discord.com',
      category: '社交',
      aliases: ['dc', 'discord'],
      domains: ['discord.com', 'discordapp.com', 'discordapp.net', 'discord.gg', 'discord.media', 'discord.co']
    },
    {
      id: 'reddit',
      name: 'Reddit',
      url: 'reddit.com',
      category: '社区',
      aliases: ['reddit', '红迪'],
      domains: ['reddit.com', 'redd.it', 'redditstatic.com', 'redditmedia.com']
    },
    {
      id: 'instagram',
      name: 'Instagram',
      url: 'instagram.com',
      category: '社交',
      aliases: ['ins', 'ig', 'instagram'],
      domains: ['instagram.com', 'cdninstagram.com', 'ig.me']
    },
    {
      id: 'whatsapp',
      name: 'WhatsApp',
      url: 'whatsapp.com',
      category: '社交',
      aliases: ['wa', 'whatsapp'],
      domains: ['whatsapp.com', 'whatsapp.net', 'wa.me']
    },
    {
      id: 'facebook-meta',
      name: 'Meta / Facebook',
      url: 'facebook.com',
      category: '社交',
      aliases: ['fb', 'meta', 'facebook', 'threads'],
      domains: ['facebook.com', 'fbcdn.net', 'fb.com', 'fbsbx.com', 'messenger.com', 'meta.com', 'threads.net']
    },
    {
      id: 'tiktok',
      name: 'TikTok',
      url: 'tiktok.com',
      category: '视频',
      aliases: ['tiktok', '海外抖音'],
      domains: ['tiktok.com', 'tiktokcdn.com', 'tiktokcdn-us.com', 'tiktokv.com', 'musical.ly']
    },
    {
      id: 'twitch',
      name: 'Twitch',
      url: 'twitch.tv',
      category: '直播',
      aliases: ['twitch'],
      domains: ['twitch.tv', 'ttvnw.net', 'jtvnw.net', 'ext-twitch.tv']
    },
    {
      id: 'netflix',
      name: 'Netflix',
      url: 'netflix.com',
      category: '视频',
      aliases: ['nf', '奈飞', '网飞', 'netflix'],
      domains: ['netflix.com', 'nflxvideo.net', 'nflximg.net', 'nflximg.com', 'nflxso.net', 'nflxext.com']
    },
    {
      id: 'spotify',
      name: 'Spotify',
      url: 'spotify.com',
      category: '音乐',
      aliases: ['spotify', '声田'],
      domains: ['spotify.com', 'scdn.co', 'spotifycdn.com', 'spoti.fi']
    },
    {
      id: 'disney',
      name: 'Disney+',
      url: 'disneyplus.com',
      category: '视频',
      aliases: ['disney', '迪士尼'],
      domains: ['disneyplus.com', 'disney-plus.net', 'bamgrid.com', 'dssott.com']
    },
    {
      id: 'steam',
      name: 'Steam',
      url: 'store.steampowered.com',
      category: '游戏',
      aliases: ['steam', 'valve', 'v社'],
      domains: ['steampowered.com', 'steamcommunity.com', 'steamstatic.com', 'steamcontent.com', 'steamserver.net', 'steamgames.com', 'valvesoftware.com']
    },
    {
      id: 'epic-games',
      name: 'Epic Games',
      url: 'epicgames.com',
      category: '游戏',
      aliases: ['epic', 'unreal'],
      domains: ['epicgames.com', 'epicgames.dev', 'unrealengine.com']
    },
    {
      id: 'playstation',
      name: 'PlayStation',
      url: 'playstation.com',
      category: '游戏',
      aliases: ['psn', 'ps5', 'sony', '索尼'],
      domains: ['playstation.com', 'playstation.net', 'sonyentertainmentnetwork.com']
    },
    {
      id: 'xbox',
      name: 'Xbox',
      url: 'xbox.com',
      category: '游戏',
      aliases: ['xbox'],
      domains: ['xbox.com', 'xboxservices.com']
    },
    {
      id: 'nintendo',
      name: '任天堂 / Nintendo',
      url: 'nintendo.com',
      category: '游戏',
      aliases: ['switch', 'nintendo', '任天堂'],
      domains: ['nintendo.com', 'nintendo.net', 'nintendo.co.jp']
    },
    {
      id: 'riot',
      name: '拳头游戏 / Riot',
      url: 'riotgames.com',
      category: '游戏',
      aliases: ['riot', 'lol', 'valorant', '瓦罗兰特'],
      domains: ['riotgames.com', 'riotcdn.net', 'leagueoflegends.com', 'playvalorant.com']
    },
    {
      id: 'roblox',
      name: 'Roblox',
      url: 'roblox.com',
      category: '游戏',
      aliases: ['roblox'],
      domains: ['roblox.com', 'rbxcdn.com']
    },
    {
      id: 'notion',
      name: 'Notion',
      url: 'notion.so',
      category: '办公',
      aliases: ['notion'],
      domains: ['notion.so', 'notion.site', 'notion-static.com']
    },
    {
      id: 'figma',
      name: 'Figma',
      url: 'figma.com',
      category: '设计',
      aliases: ['figma'],
      domains: ['figma.com', 'figma-alpha.com']
    },
    {
      id: 'slack',
      name: 'Slack',
      url: 'slack.com',
      category: '办公',
      aliases: ['slack'],
      domains: ['slack.com', 'slack-edge.com', 'slack-imgs.com']
    },
    {
      id: 'zoom',
      name: 'Zoom',
      url: 'zoom.us',
      category: '办公',
      aliases: ['zoom'],
      domains: ['zoom.us', 'zoom.com', 'zoomgov.com']
    },
    {
      id: 'dropbox',
      name: 'Dropbox',
      url: 'dropbox.com',
      category: '云盘',
      aliases: ['dropbox'],
      domains: ['dropbox.com', 'dropboxstatic.com', 'dropboxusercontent.com']
    },
    {
      id: 'linkedin',
      name: 'LinkedIn',
      url: 'linkedin.com',
      category: '职场',
      aliases: ['linkedin', '领英'],
      domains: ['linkedin.com', 'licdn.com']
    },
    {
      id: 'wikipedia',
      name: '维基百科',
      url: 'wikipedia.org',
      category: '百科',
      aliases: ['wiki', 'wikipedia', '维基'],
      domains: ['wikipedia.org', 'wikimedia.org', 'wiktionary.org', 'wikidata.org']
    },
    {
      id: 'amazon',
      name: 'Amazon',
      url: 'amazon.com',
      category: '购物',
      aliases: ['amazon', '亚马逊'],
      domains: ['amazon.com', 'amazon.co.jp', 'amazon.co.uk', 'amazon.cn', 'media-amazon.com', 'images-amazon.com', 'ssl-images-amazon.com', 'a2z.com']
    },
    {
      id: 'aws',
      name: 'Amazon AWS',
      url: 'aws.amazon.com',
      category: '云服务',
      aliases: ['aws', 'cloudfront', 's3'],
      domains: ['amazonaws.com', 'aws.amazon.com', 'cloudfront.net', 'awsstatic.com', 'aws.dev']
    },
    {
      id: 'cloudflare',
      name: 'Cloudflare',
      url: 'cloudflare.com',
      category: 'CDN / 基础设施',
      aliases: ['cf', 'cloudflare', 'warp'],
      domains: ['cloudflare.com', 'cloudflare.net', 'cloudflare-dns.com', 'cloudflareinsights.com', 'cloudflareclient.com', 'workers.dev', 'pages.dev', 'r2.dev', 'cf-ipfs.com', 'one.one.one.one']
    },
    {
      id: 'akamai',
      name: 'Akamai CDN',
      url: 'akamai.com',
      category: 'CDN / 基础设施',
      aliases: ['akamai'],
      domains: ['akamai.net', 'akamaiedge.net', 'akamaihd.net', 'akamaized.net', 'akadns.net', 'edgekey.net', 'edgesuite.net', 'akamaitechnologies.com']
    },
    {
      id: 'fastly',
      name: 'Fastly CDN',
      url: 'fastly.com',
      category: 'CDN / 基础设施',
      aliases: ['fastly'],
      domains: ['fastly.net', 'fastlylb.net', 'fastly.com']
    },
    {
      id: 'vercel-netlify',
      name: 'Vercel / Netlify',
      url: 'vercel.com',
      category: '云服务',
      aliases: ['vercel', 'netlify', 'supabase'],
      domains: ['vercel.com', 'vercel.app', 'vercel-insights.com', 'netlify.com', 'netlify.app', 'supabase.co', 'supabase.com']
    },
    {
      id: 'nvidia',
      name: 'NVIDIA',
      url: 'nvidia.com',
      category: '硬件',
      aliases: ['nv', 'nvidia', '英伟达', 'geforce'],
      domains: ['nvidia.com', 'nvidia.cn', 'nvgs.nvidia.com', 'geforce.com']
    },
    {
      id: 'mozilla',
      name: 'Mozilla / Firefox',
      url: 'mozilla.org',
      category: '浏览器',
      aliases: ['firefox', 'mozilla', '火狐'],
      domains: ['mozilla.org', 'mozilla.com', 'mozilla.net', 'firefox.com']
    },
    {
      id: 'paypal-stripe',
      name: 'PayPal / Stripe',
      url: 'paypal.com',
      category: '支付',
      aliases: ['paypal', 'stripe'],
      domains: ['paypal.com', 'paypalobjects.com', 'stripe.com', 'stripe.network']
    },
    {
      id: 'sentry-telemetry',
      name: 'Sentry / 监控遥测',
      url: 'sentry.io',
      category: '监控',
      aliases: ['sentry', 'datadog', 'telemetry'],
      domains: ['sentry.io', 'datadoghq.com', 'segment.io', 'mixpanel.com', 'amplitude.com']
    },
    {
      id: 'ntp-time',
      name: '时间同步 (NTP)',
      url: 'pool.ntp.org',
      category: '基础设施',
      aliases: ['ntp', 'time'],
      domains: ['pool.ntp.org', 'time.windows.com', 'time.apple.com', 'time.google.com', 'time.cloudflare.com', 'ntp.aliyun.com', 'ntp.tencent.com']
    },
    {
      id: 'public-dns-cf',
      name: 'Cloudflare DNS',
      url: '1.1.1.1',
      category: '公共 DNS',
      aliases: ['cf dns', '1.1.1.1'],
      domains: [],
      ips: ['1.1.1.1', '1.0.0.1', '2606:4700:4700::1111', '2606:4700:4700::1001']
    },
    {
      id: 'public-dns-google',
      name: 'Google DNS',
      url: '8.8.8.8',
      category: '公共 DNS',
      aliases: ['google dns', '8.8.8.8'],
      domains: [],
      ips: ['8.8.8.8', '8.8.4.4', '2001:4860:4860::8888', '2001:4860:4860::8844']
    },
    {
      id: 'public-dns-ali',
      name: '阿里公共 DNS',
      url: 'alidns.com',
      category: '公共 DNS',
      aliases: ['alidns', '223.5.5.5'],
      domains: [],
      ips: ['223.5.5.5', '223.6.6.6']
    },
    {
      id: 'public-dns-dnspod',
      name: '腾讯 DNSPod',
      url: 'dnspod.cn',
      category: '公共 DNS',
      aliases: ['dnspod', '119.29.29.29'],
      domains: [],
      ips: ['119.29.29.29', '119.28.28.28']
    },
    {
      id: 'public-dns-114',
      name: '114 DNS',
      url: '114dns.com',
      category: '公共 DNS',
      aliases: ['114dns', '114.114.114.114'],
      domains: [],
      ips: ['114.114.114.114', '114.114.115.115']
    },
    {
      id: 'public-dns-quad9',
      name: 'Quad9 DNS',
      url: 'quad9.net',
      category: '公共 DNS',
      aliases: ['quad9', '9.9.9.9'],
      domains: ['quad9.net'],
      ips: ['9.9.9.9', '149.112.112.112']
    }
  ];

  // 构建 O(label) 后缀查表索引，确保最长后缀优先且严格按点边界匹配。
  // Build O(label) suffix lookup index enforcing longest-suffix precedence and dot-boundary matching.
  const domainIndex = new Map();
  const ipIndex = new Map();
  for (const entry of ENTRIES) {
    const info = Object.freeze({
      id: entry.id,
      name: entry.name,
      url: entry.url,
      category: entry.category,
      aliases: Object.freeze(entry.aliases || [])
    });
    for (const rawDomain of entry.domains || []) {
      const domain = rawDomain.toLowerCase().replace(/\.+$/, '');
      if (domain && !domainIndex.has(domain)) {
        domainIndex.set(domain, { ...info, matchedDomain: domain });
      }
    }
    for (const rawIp of entry.ips || []) {
      const ip = rawIp.toLowerCase();
      if (ip && !ipIndex.has(ip)) {
        ipIndex.set(ip, { ...info, matchedDomain: ip });
      }
    }
  }

  function normalizeHost(raw) {
    if (typeof raw !== 'string') return '';
    let host = raw.trim().toLowerCase().replace(/\.+$/, '');
    if (host.startsWith('[') && host.endsWith(']')) {
      host = host.slice(1, -1);
    }
    return host;
  }

  function isIpLiteral(host) {
    if (!host) return false;
    if (host.includes(':')) return true;
    return /^\d{1,3}(\.\d{1,3}){3}$/.test(host);
  }

  function resolveSite(rawHost) {
    const host = normalizeHost(rawHost);
    if (!host || host === '未知目标' || host === '未知') return null;
    if (isIpLiteral(host)) {
      return ipIndex.get(host) || null;
    }
    // 沿点边界从最具体子域名逐级向上查表，避免 notgithub.com 误命中 github.com。
    // Walk dot boundaries from most-specific to broadest suffix so notgithub.com never hits github.com.
    let cursor = host;
    while (cursor) {
      const hit = domainIndex.get(cursor);
      if (hit) return hit;
      const dot = cursor.indexOf('.');
      if (dot < 0) break;
      cursor = cursor.slice(dot + 1);
    }
    return null;
  }

  const api = {
    entries: ENTRIES,
    domainCount: domainIndex.size,
    resolveSite,
    normalizeHost
  };
  if (typeof module !== 'undefined') module.exports = api;
  root.SiteDictionary = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
