# APK 校验值

下载 Release 附件后可用以下命令核对（Windows 用 `certutil -hashfile <文件> SHA256`）：

```bash
sha256sum XModeEnabler-vX.Y-release.apk
```

| 版本 | 文件 | 大小 | SHA-256 |
|---|---|---|---|
| v1.6 | `XModeEnabler-v1.6-release.apk` | 54,156 B | `747bfc1a67537590cd3016c4970b03765bc50246e1eecc3d6cf15a1b3564dc5e` |
| v1.5 | `XModeEnabler-v1.5-release.apk` | 52,352 B | `e21526dd1d0a34a3a189c8984117f8a5bea7b05cc3e351f29763039695ed8bee` |
| v1.1 | `XModeEnabler-v1.1-release.apk` | 41,663 B | `c199f5b2c3bce238051c476a44445bc331fd7deb89edc3706a10d31032d86a7f`（debug 签名） |

> v1.5 及以后的包使用同一把正式密钥（证书 SHA-256 `c2f87c95ba6c289f406b1922803e15c1a6ace75e73b78794ebb7eab7c46ba625`），
> 与 v1.1 的 debug 签名**互不兼容**，跨签名升级需先卸载。
