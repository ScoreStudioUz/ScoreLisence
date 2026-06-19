package uz.score.scorecheck.logger;

import uz.score.scorecheck.ScoreCheck;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Logs all check events to plugins/ScoreCheck/logs/scorecheck.log
 */
public class CheckLogger {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final File logFile;

    public CheckLogger(ScoreCheck plugin) {
        File logDir = new File(plugin.getDataFolder(), "logs");
        if (!logDir.exists()) logDir.mkdirs();
        this.logFile = new File(logDir, "scorecheck.log");
    }

    public void log(String event, String player, String moderator, String extra) {
        String line = String.format("[%s] [%s] Player=%s | Moderator=%s%s",
                LocalDateTime.now().format(DT),
                event,
                player,
                moderator,
                extra != null && !extra.isBlank() ? " | " + extra : ""
        );
        write(line);
        ScoreCheck.getInstance().getLogger().info(line);
    }

    public void logStart(String player, String moderator) {
        log("CHECK_START", player, moderator, null);
    }

    public void logPass(String player, String moderator) {
        log("CHECK_PASS", player, moderator, null);
    }

    public void logBan(String player, String moderator, String reason) {
        log("CHECK_BAN", player, moderator, "Reason=" + reason);
    }

    public void logTimeout(String player, String moderator) {
        log("CHECK_TIMEOUT", player, moderator, null);
    }

    public void logDisconnect(String player, String moderator) {
        log("CHECK_DISCONNECT", player, moderator, null);
    }

    public void logPhase2(String player, String moderator) {
        log("PHASE2", player, moderator, null);
    }

    private void write(String line) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(logFile, true))) {
            bw.write(line);
            bw.newLine();
        } catch (IOException e) {
            ScoreCheck.getInstance().getLogger().warning("Failed to write to check log: " + e.getMessage());
        }
    }
}
