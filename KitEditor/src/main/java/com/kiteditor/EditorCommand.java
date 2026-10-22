package com.kiteditor;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class EditorCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    private final EditorManager editorManager;

    public EditorCommand(JavaPlugin plugin, EditorManager editorManager) {
        this.plugin = plugin;
        this.editorManager = editorManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cТолько игроки могут использовать эту команду!");
            return true;
        }

        Player player = (Player) sender;
        editorManager.openKitList(player, 0);
        return true;
    }
}
