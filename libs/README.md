# libs

这个目录不再存放任何第三方二进制，项目也不再提交第三方 jar。

- 上游曾把 `modularui2-2.2.2-1.7.10-dev.jar`（LGPL-3.0）提交在这里，现在改成从 GTNH Maven
  取 `com.github.GTNewHorizons:ModularUI2`，仓库里不再需要它。
- 上游曾把 InputFix（`lain.mods.inputfix`，zlainsama，MMPL 1.0.1）的 jar 也放在这里，现在同样
  不再提交。它只是**可选**的客户端前置：安装 InputFix 或 lwjgl3ify 后，1.7.10 的键盘事件才会
  携带输入法合成的字符，搜索框中文输入才可用；两者都没有时模组照常工作，只是不能输入中文
  （启动时日志会给出提示）。
- 本地开发要测中文输入时，把自己构建的 `InputFix-1.7.10-v6.jar` 放到本目录即可：
  `dependencies.gradle` 只在文件存在时才把它加进开发环境类路径。`libs/*.jar` 已被
  `.gitignore` 忽略，因此它不会被提交、也不会被再分发。

第三方组件的来源与许可证见仓库根目录的 `THIRD-PARTY-NOTICES.md`。
