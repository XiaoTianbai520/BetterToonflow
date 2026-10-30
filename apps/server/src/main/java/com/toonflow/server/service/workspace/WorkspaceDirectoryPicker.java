package com.toonflow.server.service.workspace;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.server.common.BusinessException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceDirectoryPicker {
    private final ObjectMapper json;

    public WorkspaceDirectoryPicker(ObjectMapper json) {
        this.json = json;
    }

    public synchronized Selection select() throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        List<String> command;
        if (os.contains("win")) {
            String session = System.getenv("SESSIONNAME");
            if (session != null && session.equalsIgnoreCase("Services")) {
                return new Selection(false, null);
            }
            // 独立 STA 子进程调用系统目录选择器，不依赖 Spring 默认设置的 java.awt.headless。
            command = List.of("powershell.exe", "-NoProfile", "-STA", "-NonInteractive", "-Command", """
                    $ErrorActionPreference = 'Stop'
                    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
                    if (-not [Environment]::UserInteractive) {
                        @{ native = $false; directory = $null } | ConvertTo-Json -Compress
                        exit 0
                    }
                    Add-Type -AssemblyName System.Windows.Forms
                    $picker = [System.Windows.Forms.FolderBrowserDialog]::new()
                    try {
                        $directory = $null
                        if ($picker.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
                            $directory = $picker.SelectedPath
                        }
                        @{ native = $true; directory = $directory } | ConvertTo-Json -Compress
                    } finally { $picker.Dispose() }
                    """);
        } else if (os.contains("mac")) {
            if (System.getenv("SSH_CONNECTION") != null || !hasMacGuiSession()) {
                return new Selection(false, null);
            }
            command = List.of("/usr/bin/osascript", "-e", """
                    try
                        POSIX path of (choose folder)
                    on error number -128
                        return ""
                    end try
                    """);
        } else {
            if ((System.getenv("DISPLAY") == null && System.getenv("WAYLAND_DISPLAY") == null)
                    || Files.exists(Path.of("/.dockerenv"))) {
                return new Selection(false, null);
            }
            Path executable = executable("zenity");
            if (executable != null) {
                command = List.of(executable.toString(), "--file-selection", "--directory");
            } else {
                executable = executable("kdialog");
                if (executable == null) {
                    return new Selection(false, null);
                }
                command = List.of(executable.toString(), "--getexistingdirectory");
            }
        }
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            int exit = process.waitFor();
            if (exit == 1 && !os.contains("win") && !os.contains("mac")) {
                return new Selection(true, null);
            }
            if (exit != 0) {
                throw new BusinessException(500, "唤起系统目录选择器失败", null);
            }
            return os.contains("win") ? json.readValue(output, Selection.class)
                    : new Selection(true, output.isEmpty() ? null : output);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("目录选择被中断", exception);
        } finally {
            process.destroy();
        }
    }

    private boolean hasMacGuiSession() throws IOException {
        Process process = new ProcessBuilder("/usr/bin/stat", "-f", "%Su", "/dev/console")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            String owner = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return owner.equals(System.getProperty("user.name")) && !owner.equals("root");
        } finally {
            process.destroy();
        }
    }

    private Path executable(String name) {
        String searchPath = System.getenv("PATH");
        if (searchPath != null) {
            for (String directory : searchPath.split(java.io.File.pathSeparator)) {
                Path candidate = Path.of(directory).resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    public record Selection(@JsonProperty("native") boolean nativeSupported, String directory) {
    }
}
