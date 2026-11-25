# Fcmfix for ColorOS17

优化 FCM 在 ColorOS17 的使用体验。

适配 Material 3 Expressive UI 和 COUI。

## 功能

- 在系统广播投递路径中识别可信 FCM 广播，允许名单中的应用被唤醒
- 处理 ColorOS 的自启动、Hans 冻结、代理唤醒和相关后台限制
- 将 Google 服务加入睡眠待机优化白名单，并默认解除 Google 网络限制
- 留通知、解除冻结应用状态及唤醒失败提示

## 来源

基于 [Artifical0/fcmfix-coloros](https://github.com/Artifical0/fcmfix-coloros)，源自 [kooritea/fcmfix](https://github.com/kooritea/fcmfix)。

UI 参考 [KernelSU](https://github.com/tiann/KernelSU)、[LSPosed](https://github.com/LSPosed/LSPosed) 和 [COUI](https://github.com/suqi8/coui)。
