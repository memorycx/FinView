package com.finview.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 统一的 Python 脚本执行器：
 * 1. ProcessBuilder 起子进程跑 python 命令
 * 2. 命令行参数传递，stdout 读回 JSON
 * 3. 带超时控制 + 错误输出捕获
 */
@Component
public class PythonRunner {

    // 使用示例如下：
    public static void main(String[] args) throws Exception {
        // 直接 new 出 PythonRunner（不走 Spring 容器，方便单独调试）
        PythonRunner runner = new PythonRunner(
                "python",
                "",   // 空字符串 → 默认走 src/main/resources/python
                new ObjectMapper()
        );

        // 方式一：List.of() 一行搞定
        JsonNode result = runner.run("fund_info.py", List.of("016452"));

        // 方式二：先创建 ArrayList 再 add
        // List<String> params = new ArrayList<>();
        // params.add("016452");
        // JsonNode result = runner.run("fund_info.py", params);

        System.out.println(result.toPrettyString());
    }

    /** Python 可执行文件路径（默认 python，Windows 上也可以配成 python3） */
    private final String pythonCmd;

    /** Python 脚本根目录 */
    private final Path scriptDir;

    /** JSON 解析（Spring Boot 自带 Jackson，直接注入） */
    private final ObjectMapper mapper;

    /** 单脚本最长执行时间（秒），防止第三方接口卡死 */
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    public PythonRunner(
            @Value("${finview.python.cmd:python}") String pythonCmd,
            @Value("${finview.python.script-dir:}") String scriptDir,
            ObjectMapper mapper) {
        this.pythonCmd = pythonCmd;
        // 脚本目录：优先读配置，否则默认 resources/python
        if (scriptDir.isBlank()) {
            this.scriptDir = Paths.get("src", "main", "resources", "python").toAbsolutePath();
        } else {
            this.scriptDir = Paths.get(scriptDir).toAbsolutePath();
        }
        this.mapper = mapper;
    }

    /**
     * 执行脚本并返回解析后的 JSON 对象，使用默认超时（30 秒）。
     *
     * @param scriptName 脚本文件名，如 "fund_info.py"
     * @param args       传给脚本的命令行参数，如 List.of("016452")
     * @return 脚本输出的 JSON
     */
    public JsonNode run(String scriptName, List<String> args) throws Exception {
        return run(scriptName, args, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * 执行脚本并返回解析后的 JSON 对象。
     *
     * @param timeoutSeconds 本次调用的最长执行时间：挡在用户请求路径上的脚本（比如登录时要用的
     *                       交易日历）要调小，否则外部接口一卡，用户就得干等默认的 30 秒
     * @return 脚本输出的 JSON
     */
    public JsonNode run(String scriptName, List<String> args, long timeoutSeconds) throws Exception {
        Path scriptPath = scriptDir.resolve(scriptName);
        if (!Files.exists(scriptPath)) {
            throw new IllegalStateException("脚本不存在: " + scriptPath);
        }

        // 组装命令：python <script> <arg1> <arg2> ...
        ProcessBuilder pb = new ProcessBuilder();
        pb.command(buildCommand(scriptPath, args));
        pb.redirectErrorStream(true);   // 把 stderr 合并到 stdout，方便一把读

        Process process = pb.start();

        // 读输出
        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            output = sb.toString().trim();
        }

        // 等进程结束（带超时）
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Python 脚本执行超时: " + scriptName);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Python 脚本执行失败:\n" + output);
        }

        // 解析 JSON
        return mapper.readTree(output);
    }

    private List<String> buildCommand(Path scriptPath, List<String> args) {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(pythonCmd);
        cmd.add(scriptPath.toString());
        cmd.addAll(args);
        return cmd;
    }
}