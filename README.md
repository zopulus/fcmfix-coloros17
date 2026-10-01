# Fcmfix for ColorOS17

优化 FCM 在 ColorOS17 的使用体验。

适配 Material 3 Expressive UI。

## 功能

- 在系统广播投递路径中识别可信 FCM 广播，允许名单中的应用被唤醒
- 处理 ColorOS 的自启动、Hans 冻结、代理唤醒和相关后台限制
- 将 Google 服务加入睡眠待机优化白名单，并默认解除 Google 网络限制
- 可选将 UID 0 进程加入联网白名单，依赖主白名单开关
- 保留通知、解除冻结应用状态及唤醒失败提示

## 来源

基于 [Artifical0/fcmfix-coloros](https://github.com/Artifical0/fcmfix-coloros)，源自 [kooritea/fcmfix](https://github.com/kooritea/fcmfix)。

界面参考 [KernelSU](https://github.com/tiann/KernelSU) 与 [LSPosed](https://github.com/LSPosed/LSPosed)。

图标使用 Google Material Symbols 和 Firebase 标识。Material Symbols 的 Apache 2.0 许可证保留在 `app/src/main/assets/licenses/`。
