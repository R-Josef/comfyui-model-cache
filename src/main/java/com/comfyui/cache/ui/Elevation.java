package com.comfyui.cache.ui;

import com.comfyui.cache.LinkSupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 以管理员身份运行 PowerShell 脚本。
 *
 * <p>这里有两个容易踩的坑：
 * <ul>
 *   <li>Windows PowerShell 5.1 只有在脚本带 UTF-8 BOM 时才按 UTF-8 读取，
 *       否则会把中文路径当成本地编码（GBK），路径全部对不上，脚本一闪就退出。
 *       本类的所有临时脚本都会写入 BOM。</li>
 *   <li>提权进程默认会弹出控制台窗口，这里统一加 {@code -WindowStyle Hidden}。</li>
 * </ul>
 *
 * <p>脚本通过一个状态文件回报结果（OK / FAIL …），调用方据此给用户反馈，
 * 而不是像以前那样只能看到窗口一闪。
 */
public final class Elevation {

    /** 脚本执行结果。 */
    public record Result(boolean success, String detail) {
    }

    private Elevation() {
    }

    /**
     * 阻塞执行：启动提权 PowerShell 运行 {@code scriptBody}，等待脚本把结果写进 {@code statusFile}。
     * 必须在后台线程里调用（会等待用户在 UAC 窗口上做选择）。
     */
    public static Result runScript(String scriptBody, Path statusFile, long uacTimeoutSeconds) {
        try {
            Files.deleteIfExists(statusFile);
            Path script = Files.createTempFile("comfyui-cache-elevate-", ".ps1");
            script.toFile().deleteOnExit();
            writeWithBom(script, scriptBody);

            String invocation = "Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList "
                    + "@('-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File','"
                    + "\"" + psQuote(script.toString()) + "\"')";
            Process process = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-WindowStyle", "Hidden",
                    "-ExecutionPolicy", "Bypass", "-Command", invocation)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();

            if (!process.waitFor(uacTimeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(false, "等待 UAC 授权超时。");
            }
            if (process.exitValue() != 0) {
                return new Result(false, "没有拿到管理员权限（可能点了「否」）。");
            }

            long deadline = System.currentTimeMillis() + 30_000L;
            while (System.currentTimeMillis() < deadline) {
                if (Files.exists(statusFile)) {
                    String text = Files.readString(statusFile, StandardCharsets.UTF_8).trim();
                    if (text.startsWith("OK")) {
                        return new Result(true, text);
                    }
                    String detail = text.startsWith("FAIL") ? text.substring(4).trim() : text;
                    return new Result(false, detail.isEmpty() ? "管理员进程报告失败。" : detail);
                }
                Thread.sleep(200);
            }
            return new Result(false, "管理员进程没有返回结果（可能被安全软件拦住了）。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "操作被中断。");
        } catch (Exception e) {
            return new Result(false, LinkSupport.describe(e));
        }
    }

    /**
     * 生成「启动某个程序」的脚本内容。参数里带空格的会自动加双引号，
     * 中文路径由调用方写入时统一加 BOM 解决。
     */
    public static String buildStartScript(String executable, List<String> arguments,
                                          Path workingDirectory, Path statusFile) {
        StringBuilder argumentList = new StringBuilder();
        for (String argument : arguments) {
            if (argumentList.length() > 0) {
                argumentList.append(',');
            }
            String value = argument.contains(" ") ? "\"" + argument + "\"" : argument;
            argumentList.append('\'').append(psQuote(value)).append('\'');
        }
        return String.join("\n",
                "$ErrorActionPreference = 'Stop'",
                "try {",
                "    Start-Process -FilePath '" + psQuote(executable) + "'"
                        + " -ArgumentList @(" + argumentList + ")"
                        + " -WorkingDirectory '" + psQuote(workingDirectory.toString()) + "' | Out-Null",
                "    [IO.File]::WriteAllText('" + psQuote(statusFile.toString()) + "', 'OK')",
                "} catch {",
                "    [IO.File]::WriteAllText('" + psQuote(statusFile.toString())
                        + "', 'FAIL ' + $_.Exception.Message)",
                "}");
    }

    /** 生成「写注册表开关」的脚本内容。 */
    public static String buildRegistryScript(Path statusFile) {
        return String.join("\n",
                "$ErrorActionPreference = 'Stop'",
                "try {",
                "    New-ItemProperty -Path 'HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\AppModelUnlock'"
                        + " -Name AllowDevelopmentWithoutDevLicense -PropertyType DWord -Value 1 -Force | Out-Null",
                "    [IO.File]::WriteAllText('" + psQuote(statusFile.toString()) + "', 'OK')",
                "} catch {",
                "    [IO.File]::WriteAllText('" + psQuote(statusFile.toString())
                        + "', 'FAIL ' + $_.Exception.Message)",
                "}");
    }

    /** PowerShell 单引号字符串里，单引号要用两个单引号转义。 */
    public static String psQuote(String value) {
        return value.replace("'", "''");
    }

    /**
     * Windows PowerShell 5.1 判断脚本编码的办法是「有没有 BOM」，
     * 不带 BOM 的 UTF-8 会被当成 GBK，中文路径直接失效。
     */
    private static void writeWithBom(Path file, String content) throws Exception {
        Files.write(file, ("\uFEFF" + content).getBytes(StandardCharsets.UTF_8));
    }
}
