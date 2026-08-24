package id.kuru.sussmp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Proteksi: blokir End sebelum 3 bos tumbang, kill detection, keluar game. */
final class GameRulesListener implements Listener {

    private final SusPlugin plugin;

    GameRulesListener(SusPlugin plugin) {
        this.plugin = plugin;
        org.bukkit.Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** Kunci The End sampai Wither mati (task ke-3). */
    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (plugin.phase != SusPlugin.Phase.PLAYING && plugin.phase != SusPlugin.Phase.MEETING) return;
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL
                && e.getCause() != PlayerTeleportEvent.TeleportCause.END_GATEWAY) return;
        // izinkan End hanya jika sudah di task terakhir (dragon)
        boolean dragonPhase = plugin.currentTask != null
                && "Ender Dragon".equals(plugin.taskTitle());
        if (!dragonPhase) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(SusPlugin.PREFIX + ChatColor.RED
                    + "The End masih terkunci! Tuntaskan: " + ChatColor.YELLOW + plugin.taskTitle());
        }
    }

    /** Innocent dibunuh impostor → mati tanpa death message (rahasia tetap terjaga). */
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!plugin.players.contains(p.getUniqueId())) return;
        e.deathMessage(null); // tidak ada death message saat game jalan
        if (p.getKiller() != null && plugin.impostor.getOrDefault(p.getKiller().getUniqueId(), false)) {
            // dibunuh impostor — jalankan alur kill
            Bukkit.getScheduler().runTask(plugin, () -> plugin.killPlayer(p));
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.alive.getOrDefault(p.getUniqueId(), false)) {
                    plugin.alive.put(p.getUniqueId(), false);
                    p.sendMessage(SusPlugin.PREFIX + ChatColor.GRAY + "Kau mati karena sebab alami…");
                    plugin.checkWinConditions();
                }
            });
        }
    }

    /** Mayat ditemukan impostor? (opsional sederhana: impostor membunuh → meeting otomatis oleh korban tidak ada; crew klik bell) */

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        if (!plugin.players.contains(u)) return;
        if (plugin.phase == SusPlugin.Phase.PLAYING || plugin.phase == SusPlugin.Phase.MEETING) {
            if (plugin.alive.getOrDefault(u, false)) {
                plugin.alive.put(u, false);
                plugin.broadcast(SusPlugin.PREFIX + ChatColor.GRAY + e.getPlayer().getName() + " keluar — dinyatakan mati.");
                plugin.checkWinConditions();
            }
        } else {
            plugin.players.remove(u);
        }
    }
}
