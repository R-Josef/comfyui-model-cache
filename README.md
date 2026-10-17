# ComfyUI 模型缓存

**ComfyUI Model Cache** — keep frequently used models on an SSD and expose them to ComfyUI
through symbolic links (or Windows directory junctions).

把常用的 ComfyUI 模型放到固态硬盘，用符号链接 / 目录联接接回原来的位置，ComfyUI 完全无感。
图形界面基于 Java 21 + Swing，程序本身不依赖任何第三方库。

## 功能

- 用图形界面浏览模型目录，可以按单个文件缓存，也可以整个分类目录一起缓存；
- 单文件模式：复制到缓存盘 → 原文件改名为 `文件名#` 备份 → 原位置建立符号链接；
- 目录模式：整个子目录搬到缓存盘 → 原位置建立目录联接（Windows 普通用户即可）；
- 缓存列表：查看、重新校验、批量取消缓存；
- 所有记录保存在一个可读的 JSON 数据文件里（模型目录、缓存目录、缓存条目）；
- 操作失败会自动回滚，不会留下半截文件。

## 环境要求

- JDK 21（`java` 在 PATH 中即可）
- Maven 3.9 或更高版本（只有构建时需要）

## 构建

构建前请先关闭正在运行的程序：Windows 会把 `target` 里的 jar 锁住，导致打包失败。

```powershell
mvn package     # 编译 + 打包，产物：target\ComfyUI模型缓存.jar
mvn verify      # 额外跑一遍自测（57 项真实文件系统检查）
mvn clean       # 清理
```

## 运行

- 双击 `run.bat`：后台启动，自动寻找 `target` 目录下的 jar；
- 双击 `run_console.bat`：带控制台启动，出错时能看到输出；
- 也可以直接双击 `target\ComfyUI模型缓存.jar`。

## 使用

1. 「模型目录」选择 ComfyUI 的 `models` 文件夹，可以添加多个，例如 `D:\ComfyUI\models`；
2. 「缓存目录」选择固态硬盘上的一个文件夹，例如 `C:\modelcache`；
3. 左侧选中一个模型文件（或整个子文件夹），点「缓存到高速盘」；
4. 想取消时，在下方「缓存列表」里选中，点「取消缓存」。

## 两种缓存模式

### 单文件模式（默认）

1. 把选中的文件复制到缓存目录，保持相对路径不变
   （`models\diffusion_models\x.safetensors` → `modelcache\diffusion_models\x.safetensors`）；
2. 把原文件改名为 `x.safetensors#`，ComfyUI 不会再扫描到它；
3. 在原位置创建链接，指回缓存盘上的文件。

取消缓存时反着来：删掉链接 → 把备份改回原名 → 删除缓存盘上的副本（上一层目录空了会一起删掉），
同时从记录里移除。

注意：原文件是以备份的形式**保留**的，所以会占用两份磁盘空间；换来的是取消缓存几乎瞬间完成。

### 整目录模式（Windows 上不需要任何权限）

选中一个文件夹再点缓存：整个文件夹的内容搬到缓存盘，原目录被替换成目录联接。
它不需要管理员权限，因为 Windows 的目录联接（junction）对普通用户开放。
适合把 `vae`、`clip`、`loras` 这类整体都要用的分类目录一次性放到固态盘上。
代价是这个目录下的所有模型都会去缓存盘，取消缓存时要把内容复制回机械硬盘（比较慢）。

## 关于权限（重要）

链接是两种模式的技术核心，但各系统规则不同：

| 系统 | 文件符号链接 | 目录符号链接 | 目录联接 |
| --- | --- | --- | --- |
| Linux / macOS | 普通用户可用 | 普通用户可用 | 不适用 |
| Windows（默认） | 需要管理员 | 需要管理员 | 普通用户可用 |
| Windows（开发者人员模式） | 普通用户可用 | 普通用户可用 | 普通用户可用 |

所以在 Windows 上第一次运行，程序顶部会提示「当前系统不允许创建符号链接」。三种解决办法，
任选其一：

1. **打开开发者人员模式**（推荐，一次设置永久有效）：
   菜单「工具 → 开启 Windows 开发者人员模式」，确认一次 UAC 即可，之后重启程序。
   等价于把注册表 `HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\AppModelUnlock`
   下的 `AllowDevelopmentWithoutDevLicense` 设为 1。
2. **以管理员身份运行**：菜单「工具 → 以管理员身份重新启动」，或者右键 `run.bat` →「以管理员身份运行」。
3. **不用单文件模式**：直接用「整目录模式」，不需要任何权限。

菜单「工具 → 链接能力诊断」会真实地试一遍并告诉你当前环境支持到什么程度。

另外：如果缓存目录和模型目录恰好是同一块磁盘，且系统不允许创建符号链接，
程序会自动退回**硬链接**（同样不需要权限，不占额外空间）。

## 需要注意的地方

- **先关掉 ComfyUI**：ComfyUI 正在读取某个模型时，Windows 不允许改名或删除它。
- **缓存目录不能放在模型目录里**，反之也不行，程序会直接拒绝，避免 ComfyUI 重复扫描出重复条目。
- **`#` 结尾的备份文件**不会显示在左侧文件树里，避免干扰；选中已缓存的模型时，
  右侧「备份文件」一行能看到它的位置。
- 手工恢复的办法：把 `x.safetensors#` 改回 `x.safetensors`，再删掉链接即可；
  程序的「重新检查」能识别这种状态。

## 数据文件

默认位置是 `%USERPROFILE%\.comfyui-model-cache\cache-db.json`（窗口右下角可点开），
里面记录了模型目录、缓存目录和所有缓存条目，是可读的 JSON，出问题可以直接手工查看。

也可以用 `--db=<路径>` 指定别的位置：

```powershell
javaw -jar "ComfyUI模型缓存.jar" --db=D:\cfg\cache-db.json
```

## 目录结构

```
pom.xml                                  Maven 构建（UTF-8、Java 21、jar 主类）
run.bat / run_console.bat                启动脚本
src/main/java/com/comfyui/cache/         核心逻辑
    CacheEngine.java                     缓存 / 取消缓存 / 状态校验，含失败回滚
    LinkSupport.java                     符号链接、目录联接、硬链接的能力探测与创建
    CacheDatabase.java                   数据文件（JSON）
    FileUtil.java                        文件工具；所有递归都先识别链接，绝不穿过链接
src/main/java/com/comfyui/cache/ui/      界面（Swing）
src/test/java/com/comfyui/cache/         自测程序（SelfCheck，不会被打进 jar）
```

## 自测

```powershell
mvn verify
```

自测会在系统临时目录里造一个沙盒（不会碰真实模型），实际验证：
单文件缓存与取消、整目录缓存与取消、链接创建失败时的回滚、损坏数据文件的处理、
带链接的目录删除不会跟随链接、空目录清理等 57 项检查。

想单独运行（例如在 IDE 里调试）：

```powershell
mvn -q test-compile exec:java "-Dexec.mainClass=com.comfyui.cache.SelfCheck"
```
