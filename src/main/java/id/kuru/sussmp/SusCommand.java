package id.kuru.sussmp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class SusCommand implements CommandExecutor, TabCompleter {

    private final SusPlugin plugin;

    SusCommand(SusPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("sus.admin")) {
            sender.sendMessage(SusPlugin.PREFIX + "Khusus admin.");
            return true;
        }
        if (args.length == 0) { usage(sender); return true; }

        switch (args[0].toLowerCase()) {
            case "add" -> {
                Player t = Bukkit.getPlayerExact(args.length > 1 ? args[1] : "");
                if (t == null) { sender.sendMessage(SusPlugin.PREFIX + "Pemain offline."); return true; }
                if (plugin.phase != SusPlugin.Phase.LOBBY) { sender.sendMessage(SusPlugin.PREFIX + "Game sedang jalan."); return true; }
                plugin.players.add(t.getUniqueId());
                sender.sendMessage(SusPlugin.PREFIX + ChatColor.AQUA + t.getName() + ChatColor.GRAY + " masuk (" + plugin.players.size() + " pemain).");
            }
            case "remove" -> {
                Player t = Bukkit.getPlayerExact(args.length > 1 ? args[1] : "");
                if (t != null) plugin.players.remove(t.getUniqueId());
                sender.sendMessage(SusPlugin.PREFIX + "Dihapus.");
            }
            case "start" -> {
                plugin.startGame();
            }
            case "stop" -> {
                if (plugin.phase == SusPlugin.Phase.LOBBY) return true;
                for (UUID u : new ArrayList<>(plugin.players)) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null && p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.SURVIVAL);
                }
                if (plugin.bossbar != null) plugin.bossbar.removeAll();
                plugin.reset();
                sender.sendMessage(SusPlugin.PREFIX + "Game dihentikan.");
            }
            case "meetingtime" -> {
                int v = intOr(args, 1, -1);
                if (v < 10) { sender.sendMessage(SusPlugin.PREFIX + "/sus meetingtime <detik>"); return true; }
                plugin.meetingDuration = v;
                plugin.getConfig().set("meeting.duration-seconds", v);
                plugin.saveConfig();
                sender.sendMessage(SusPlugin.PREFIX + "Durasi meeting = " + v + " dtk.");
            }
            case "cooldown" -> {
                int v = intOr(args, 1, -1);
                if (v < 30) { sender.sendMessage(SusPlugin.PREFIX + "/sus cooldown <detik>"); return true; }
                plugin.meetingCooldown = v;
                plugin.getConfig().set("meeting.cooldown-seconds", v);
                plugin.saveConfig();
                sender.sendMessage(SusPlugin.PREFIX + "Cooldown bell = " + v + " dtk.");
            }
            case "impostors" -> {
                int v = intOr(args, 1, -1);
                if (v < 1) { sender.sendMessage(SusPlugin.PREFIX + "/sus impostors <jumlah>"); return true; }
                plugin.impostorCount = v;
                plugin.getConfig().set("impostors", v);
                plugin.saveConfig();
                sender.sendMessage(SusPlugin.PREFIX + "Jumlah impostor = " + v);
            }
            case "setroom" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage(SusPlugin.PREFIX + "Dari dalam game."); return true; }
                plugin.meetingRoom = p.getLocation().clone().add(0, 0, 0);
                sender.sendMessage(SusPlugin.PREFIX + "Ruang meeting dipasang di sini.");
            }
            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender s) {
        s.sendMessage(SusPlugin.PREFIX + "/sus add|remove|start|stop|meetingtime|cooldown|impostors|setroom");
    }

    private int intOr(String[] a, int i, int dflt) {
        try { return Integer.parseInt(a[i]); } catch (Exception e) { return dflt; }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("add", "remove", "start", "stop", "meetingtime", "cooldown", "impostors", "setroom"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        }
        String pre = args[args.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(pre));
        return out;
    }
}
