package io.github.gk0wk.mcron;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.CommandHelp;
import co.aikar.commands.annotation.*;
import org.bukkit.command.CommandSender;

@CommandAlias("cron|mcron")
public class CronCommands extends BaseCommand {
    @Subcommand("reload")
    @CommandPermission("mcron.reload")
    @Description("{@@msg.help-reload}")
    public static void onReload(CommandSender sender) {
        MCron.getInstance().reload();
        MCron.getInstance().messageManager.printf(sender, "$msg.reload$");
    }

    @Subcommand("ls|list|show")
    @CommandAlias("lscron")
    @CommandPermission("mcron.list")
    @Description("{@@msg.help-list}")
    public static void listCron(CommandSender sender) {
        MCron.getInstance().cronManager.listCron(sender);
    }

    @HelpCommand
    public static void onHelp(CommandSender sender, CommandHelp help) {
        MCron.getInstance().messageManager.printf(sender, "$msg.help-head$");
        help.showHelp();
    }
}
