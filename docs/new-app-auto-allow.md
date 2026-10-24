# 自动放行新应用

- `autoAllowNewApps` 默认开启，旧配置缺少此键时同样默认开启。
- 仅新安装的 FCM 应用自动进入放行名单，沿用原来的两个接收器名称判断。
- 不扫描或补入已有应用；开启开关、打开应用、更新应用、通知授权和通知设置均不触发放行。
- 手动关闭后保持关闭；卸载后重新安装属于新的安装事件，可再次自动放行。
- 不改变系统通知权限，也不新增普通应用或权限控制器作用域。

系统框架在开机解锁后动态注册 `ACTION_PACKAGE_ADDED` 接收器，忽略 `EXTRA_REPLACING=true` 的更新事件。
运行时注册用于避免普通应用静态接收器的后台广播限制；管理界面关闭后仍可接收事件。
依据 [Android Intent 文档](https://developer.android.com/reference/android/content/Intent#ACTION_PACKAGE_ADDED) 和 [后台执行限制](https://developer.android.com/about/versions/oreo/background)。

Provider 只接受系统 UID 的安装报告，并核对安装包 UID、接收器和当前开关。安装不依赖通知权限。
保存队列完成落盘后才返回回执；临时失败最多重试三次。同一安装事件共用 ID，最多保留最近 64 个 ID，防止旧重试覆盖后续手动关闭。
本地和远端配置继续按 revision 同步，手动修改按差量合并，不覆盖并发安装加入的应用。

旧通知授权 Hook、设置页 Hook、专用 Provider 入口和权限控制器推荐作用域已移除。旧配置键仅保留清除逻辑。
单元测试覆盖默认开启、开关关闭、FCM 判断、更新过滤、旧配置迁移和重试去重。真实安装广播与 ColorOS17 行为仍需设备验证。
