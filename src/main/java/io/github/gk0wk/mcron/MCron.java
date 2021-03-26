package io.github.gk0wk.mcron;

import co.aikar.commands.PaperCommandManager;
import io.github.gk0wk.violet.config.ConfigManager;
import io.github.gk0wk.violet.config.ConfigUtil;
import io.github.gk0wk.violet.i18n.LanguageManager;
import io.github.gk0wk.violet.message.MessageManager;
import me.lucko.helper.Events;
import me.lucko.helper.plugin.ExtendedJavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServerLoadEvent;

import java.io.IOException;
import java.util.Locale;

public final class MCron extends ExtendedJavaPlugin {
    protected ConfigManager configManager;
    private LanguageManager languageManager;
    protected MessageManager messageManager;
    protected CronManager cronManager;

    private static MCron instance = null;
    public static MCron getInstance() {
        return instance;
    }

    @Override
    protected void load() {
        // 初始化ConfigManager
        configManager = new ConfigManager(this);
        configManager.touch("config.yml");

        // 初始化LanguageManager
        try {
            Locale locale = new Locale("config");
            languageManager = new LanguageManager(this)
                    .register(locale, "config.yml")
                    .setMajorLanguage(locale);
        } catch (LanguageManager.FileNotFoundException | ConfigManager.UnknownConfigFileFormatException | IOException e) {
            e.printStackTrace();
            this.onDisable();
        }

        // 初始化MessageManager
        messageManager = new MessageManager(this)
                .setLanguageProvider(languageManager);
        messageManager.setPlayerPrefix(messageManager.sprintf("$msg.prefix$"));

        instance = this;
    }

    @Override
    protected void enable() {
        // 初始化CommandManager - 不能在load()里面初始化！
        PaperCommandManager commandManager = new PaperCommandManager(this);
        commandManager.usePerIssuerLocale(true, false);
        try {
            commandManager.getLocales().loadYamlLanguageFile("config.yml", new Locale("config"));
        } catch (IOException | InvalidConfigurationException e) {
            e.printStackTrace();
            this.onDisable();
        }

        // 注册指令
        commandManager.registerCommand(new CronCommands());

        // 注册Cron管理器
        cronManager = new CronManager();

        Events.subscribe(ServerLoadEvent.class, EventPriority.MONITOR)
                .handler(e -> onServerStartup());
        Events.subscribe(PluginEnableEvent.class, EventPriority.MONITOR)
                .handler(e -> onPluginEnable(e.getPlugin().getDescription().getName()));
        Events.subscribe(PluginDisableEvent.class, EventPriority.MONITOR)
                .handler(e -> onPluginDisable(e.getPlugin().getDescription().getName()));
    }

    @Override
    protected void disable() {

    }

    protected void reload() {
        cronManager.reload();
    }

    protected void executeCommandsByConfig(Object... nodePath) {
        try {
            CommandSender sender = Bukkit.getConsoleSender();
            ConfigUtil.setListIfNull(configManager.get("cron.yml").getNode(nodePath))
                    .getList(Object::toString).forEach(command -> {
                messageManager.printf("$msg.execute$", command);
                Bukkit.dispatchCommand(sender, command);
            });
        } catch (IOException | ConfigManager.UnknownConfigFileFormatException e) {
            e.printStackTrace();
        }
    }

    private void onServerStartup() {
        executeCommandsByConfig("on-server-ready");
    }

    private void onPluginEnable(String pluginName) {
        executeCommandsByConfig("on-plugin-enable", pluginName);
    }

    private void onPluginDisable(String pluginName) {
        executeCommandsByConfig("on-plugin-disable", pluginName);
    }
}
