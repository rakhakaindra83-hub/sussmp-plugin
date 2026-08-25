package id.kuru.sussmp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Meeting + voting via chat. Terbanyak vote → diterusir; skip jika seri/semua skip. */
final class MeetingVote {

    private final SusPlugin plugin;
    private final String caller;
    private final String reason;
    final Map<UUID, UUID> votes = new HashMap<>(); // voter -> target (null UUID = skip)
    final List<UUID> eligible = new ArrayList<>();
    private BukkitRunnable timer;

    MeetingVote(SusPlugin plugin, String caller, String reason) {
        this.plugin = plugin;
        this.caller = caller;
        this.reason = reason;
        for (UUID u : plugin.players)
            if (plugin.alive.getOrDefault(u, false)) eligible.add(u);
    }

    void begin(int seconds) {
        plugin.broadcast(SusPlugin.PREFIX + ChatColor.GOLD + ChatColor.BOLD + "EMERGENCY MEETING! "
                + ChatColor.YELLOW + caller + ChatColor.GRAY + " " + reason);
        plugin.broadcast(ChatColor.GRAY + "Klik kepala di GUI / ketik " + ChatColor.WHITE + "!vote <nama>"
                + ChatColor.GRAY + " atau " + ChatColor.WHITE + "!skip" + ChatColor.GRAY + ". Waktu: "
                + seconds + " dtk.");
        for (UUID u : eligible) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.playSound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.6f);
        }

        timer = new BukkitRunnable() {
            int left = seconds;
            @Override public void run() {
                if (left <= 0 || phaseDone()) {
                    finish();
                    cancel();
                    return;
                }
                // countdown title tiap detik di 10 detik terakhir
                if (left <= 5 || left % 10 == 0) {
                    plugin.broadcast(ChatColor.GRAY + "Voting berakhir dalam " + ChatColor.YELLOW + left + "dtk");
                    if (left <= 5) {
                        for (UUID u : eligible) {
                            Player p = Bukkit.getPlayer(u);
                            if (p != null) p.sendTitle(ChatColor.YELLOW + "" + ChatColor.BOLD + left,
                                    "", 1, 15, 1);
                        }
                    }
                }
                left--;
            }
        };
        timer.runTaskTimer(plugin, 20L, 20L);
    }

    private boolean phaseDone() { return plugin.phase != SusPlugin.Phase.MEETING; }

    UUID votedFor(UUID voter) {
        return votes.get(voter);
    }

    int countVotes(UUID target) {
        int n = 0;
        for (UUID v : votes.values()) if (target.equals(v)) n++;
        return n;
    }

    /** Pemain yang sudah memberi suara (utk GUI refresh). */
    boolean hasVoted(UUID voter) { return votes.containsKey(voter); }

    void castVote(Player voter, UUID target /* null = skip */) {
        plugin.castVote(this, voter, target);
    }

    void finish() {
        if (phaseDone() && votes.isEmpty()) return;
        // hitung
        Map<UUID, Integer> tally = new HashMap<>();
        for (UUID v : votes.values()) tally.merge(v == null ? SKIP : v, 1, Integer::sum);

        UUID top = null; int max = 0; boolean tie = false;
        for (Map.Entry<UUID, Integer> e : tally.entrySet()) {
            if (e.getValue() > max) { max = e.getValue(); top = e.getKey(); tie = false; }
            else if (e.getValue() == max) tie = true;
        }
        // tampilkan hasil
        StringBuilder sb = new StringBuilder(SusPlugin.PREFIX + "Hasil: ");
        for (UUID u : eligible) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            sb.append(p.getName()).append("=").append(tally.getOrDefault(u, 0)).append(" ");
        }
        sb.append("SKIP=").append(tally.getOrDefault(SKIP, 0));
        plugin.broadcast(sb.toString());

        UUID ejected = (tie || top == null || top == SKIP) ? null : top;

        // tutup semua GUI voting sebelum hasil diumumkan
        for (UUID u : eligible) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.getOpenInventory().getTopInventory().getType()
                    == org.bukkit.event.inventory.InventoryType.CHEST) {
                p.closeInventory();
            }
        }

        plugin.endMeeting(ejected);
    }

    private static final UUID SKIP = new UUID(0, 0); // kunci khusus utk skip
}
