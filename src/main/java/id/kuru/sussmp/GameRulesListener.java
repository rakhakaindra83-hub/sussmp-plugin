package id.kuru.sussmp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Listener untuk game rules dasar: death, quit, dan portal The End (tanpa task bos). */
final class GameRulesListener implements Listener {

    private final SusPlugin plugin;

    GameRulesListener(SusPlugin plugin) {
        this.plugin = plugin;
        org.bukkit.Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** The End selalu terbuka karena task bos dihapus. */
    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (plugin.phase != SusPlugin.Phase.PLAYING && plugin.phase != SusPlugin.Phase.MEETING) return;
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL
                && e.getCause() != PlayerTeleportEvent.TeleportCause.END_GATEWAY) return;
        // The End selalu terbuka karena task bos dihapus. Tidak ada lagi pengecekan dragonUnlocked().
        // Jadi tidak perlu membatalkan event portal.
    }

    /** Innocent dibunuh impostor → mati tanpa death message (rahasia tetap terjaga). */
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!plugin.players.contains(p.getUniqueId())) return;
        e.getDrops().removeIf(plugin::isBell);
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