package id.kuru.sussmp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;


import java.util.UUID;

/** Tangkap !vote / !skip di chat saat meeting. */
final class ChatVoteListener implements Listener {

    private final SusPlugin plugin;

    ChatVoteListener(SusPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        if (plugin.phase != SusPlugin.Phase.MEETING || plugin.vote == null) return;
        String msg = e.getMessage().trim();
        UUID voter = e.getPlayer().getUniqueId();
        if (!plugin.players.contains(voter)) return;

        UUID target = null;
        if (msg.equalsIgnoreCase("!skip")) {
            target = null; // skip
        } else if (msg.toLowerCase().startsWith("!vote ")) {
            String name = msg.substring(6).trim();
            Player t = Bukkit.getPlayerExact(name);
            if (t == null || !plugin.players.contains(t.getUniqueId())
                    || !plugin.alive.getOrDefault(t.getUniqueId(), false)) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(SusPlugin.PREFIX + "Tidak bisa vote " + name + ".");
                return;
            }
            target = t.getUniqueId();
        } else {
            return; // chat biasa, biarkan lewat
        }

        e.setCancelled(true);
        final UUID ftarget = target;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (plugin.vote != null && plugin.phase == SusPlugin.Phase.MEETING)
                plugin.vote.castVote(e.getPlayer(), ftarget);
        });
    }
}
