package id.kuru.sussmp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /vote — buka GUI voting; hanya saat meeting. */
final class VoteCommand implements CommandExecutor {

    private final SusPlugin plugin;

    VoteCommand(SusPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(SusPlugin.PREFIX + "Khusus pemain.");
            return true;
        }
        if (plugin.phase != SusPlugin.Phase.MEETING || plugin.vote == null) {
            p.sendMessage(SusPlugin.PREFIX + ChatColor.RED + "Vote GUI hanya bisa dibuka saat meeting!");
            return true;
        }
        if (!plugin.players.contains(p.getUniqueId())
                || !plugin.alive.getOrDefault(p.getUniqueId(), false)) {
            p.sendMessage(SusPlugin.PREFIX + "Kamu tidak ikut voting.");
            return true;
        }
        plugin.voteGui.open(p);
        return true;
    }
}
