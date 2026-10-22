package com.kiteditor;

import org.bukkit.plugin.java.JavaPlugin;

public class KitEditorPlugin extends JavaPlugin {

    private KitManager kitManager;
    private EditorManager editorManager;

    @Override
    public void onEnable() {
        kitManager = new KitManager(this);
        editorManager = new EditorManager(this, kitManager);

        getCommand("editor").setExecutor(new EditorCommand(this, editorManager));
        getServer().getPluginManager().registerEvents(new EditorListener(this, editorManager, kitManager), this);

        getLogger().info("KitEditor enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("KitEditor disabled!");
    }

    public KitManager getKitManager() {
        return kitManager;
    }

    public EditorManager getEditorManager() {
        return editorManager;
    }
}
