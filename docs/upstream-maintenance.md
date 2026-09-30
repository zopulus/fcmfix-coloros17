# 上游维护与定制差异

## 来源与分支

上游为 Artifical0/fcmfix-coloros，迁移基准为 `53-coloros-10`（`e3de06d`）。默认分支 `coloros17` 维护本项目的功能和 UI 定制。

当前仓库保留完整上游提交历史，定制改动维护在 `coloros17` 分支；上游更新通过 `upstream` 获取并审阅。

```sh
git remote add upstream https://github.com/Artifical0/fcmfix-coloros.git
git fetch upstream --tags
git switch coloros17
git switch -c integrate/upstream-feature
# 选择完整功能所需的提交，处理依赖与冲突
git cherry-pick <commit>
./gradlew assembleRelease testDebugUnitTest
```

相关提交及依赖一并迁移，测试后合并到 `coloros17`。

## 必须保留的定制

| 范围 | 当前行为 |
| --- | --- |
| 系统适配 | 只维护 ColorOS17，移除 MIUI/HyperOS 专用路径 |
| API | libxposed API 102；静态作用域 system、com.oplus.battery |
| GMS 作用域 | 不在 GMS 进程安装 Hook；不恢复 GmsDeliveryFix 或自动 FCM 重连逻辑 |
| 激活判断 | 依据 LSPosed 服务连接，进程内共享服务引用 |
| FCM 名单 | 接收器为 FirebaseInstanceIdReceiver 或 AppMeasurementReceiver 的原检测规则 |
| 配置 | UID 0 依赖主白名单；保留本地配置快照、保存队列与 Provider 校验 |
| 广播与唤醒 | 保留可信 GMS UID/目标包校验、原同步调用链与当前冻结激活处理 |
| 管理界面 | Compose MD3E、动态配色、标题随页面横划、圆角菜单与按压反馈 |
| 构建 | 包名 io.github.zopulus.ffc；arm64-v8a；R8 与资源裁剪；模块入口保留规则 |

## 每次同步的检查

1. 比较新增功能与现有实现，避免重复 Hook 或无效开关
2. 检查新增反射目标和 API 102 入口是否需要 R8 保留规则
3. 测试重启后推送、目标应用未运行时唤醒、连续两次以上推送、冻结解除、息屏待机和配置切换
4. 检查关闭主白名单时 UID 0 不能开启；隐藏图标后可以从 LSPosed 打开
5. 增加版本号，更新 CHANGELOG，使用原签名密钥签名并发布；保留每次 R8 mapping 用于定位堆栈

版本名称采用构建日期（YYYYMMDD），内部 `versionCode` 独立递增。CI 与本地均安装 `platforms;android-37.2`，Release 默认启用 R8 和资源裁剪。
