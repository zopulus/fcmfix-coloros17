# 问题反馈

请在 [本仓库 Issues](https://github.com/zopulus/fcmfix-coloros17/issues) 描述系统版本、模块版本、LSPosed 版本、目标应用、发生时间与复现步骤。

先确认仅启用一个 FCMFix、系统与电池作用域已启用并重启、应用已加入允许名单、应用通知权限开启。可在首页“Google Play 服务诊断”检查 FCM 连接。

仓库 `scripts/collect-report.sh` 只读取系统状态与日志：

```sh
adb push scripts/collect-report.sh /sdcard/Download/collect-report.sh
adb shell su -c 'sh /sdcard/Download/collect-report.sh'
adb pull /sdcard/Download/fcmfix-report.txt
```

日志可能包含包名、网络地址和其他应用信息，请检查并删除隐私内容后再上传。不要上传签名密钥、账号信息或未经处理的完整系统日志。

Hook 成功与实际推送成功需要分别验证；FCM 网络连接失败和目标应用被系统拦截也需要分别定位。
