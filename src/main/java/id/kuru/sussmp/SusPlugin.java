package id.kuru.sussmp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ElderGuardian;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Warden;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class SusPlugin extends JavaPlugin implements Listener {

    static final String PREFIX = ChatColor.DARK_AQUA + "[SUS] " + ChatColor.GRAY;

    enum Phase { LOBBY, PLAYING, MEETING, ENDED }

    final Set<UUID> players = new HashSet<>();
    final Map<UUID, Boolean> alive = new HashMap<>();   // uuid -> hidup?
    final Map<UUID, Boolean> impostor = new HashMap<>();
    final Map<UUID, Location> preMeeting = new HashMap<>();
    final Deque<NamespacedKey> taskQueue = new ArrayDeque<>();
    NamespacedKey currentTask;

    Phase phase = Phase.LOBBY;
    final List<Location> placedHeads = new ArrayList<>();   // kepala Steve pasca-vote, dibersihkan saat reset
    Location meetingRoom;
    long lastMeeting = -1;          // tick terakhir meeting dibuka
    int meetingDuration = 60;       // detik
    int meetingCooldown = 300;      // detik
    int impostorCount = 1;

    BossBar bossbar;
    MeetingVote vote;               // aktif saat MEETING
    Random rng = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        meetingDuration = getConfig().getInt("meeting.duration-seconds", 60);
        meetingCooldown = getConfig().getInt("meeting.cooldown-seconds", 300);
        impostorCount  = getConfig().getInt("impostors", 1);

        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("sus");
        var exec = new SusCommand(this);
        cmd.setExecutor(exec);
        cmd.setTabCompleter(exec);

        getLogger().info("SusSMP aktif — jangan percaya siapa pun.");
    }

    /* ---------- item bell (emergency meeting) ---------- */

    ItemStack bellItem() {
        ItemStack it = new ItemStack(Material.BELL);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.GOLD + "Emergency Bell");
        m.setLore(List.of(ChatColor.GRAY + "Klik kanan untuk memanggil meeting.",
                ChatColor.GRAY + "Cooldown " + meetingCooldown / 60 + " menit."));
        m.addEnchant(Enchantment.UNBREAKING, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        it.setItemMeta(m);
        return it;
    }

    boolean isBell(ItemStack it) {
        return it != null && it.getType() == Material.BELL
                && it.hasItemMeta() && it.getItemMeta().hasDisplayName()
                && it.getItemMeta().getDisplayName().contains("Emergency Bell");
    }

    /* ---------- alur game ---------- */

    void startGame() {
        List<UUID> list = new ArrayList<>(players);
        if (list.size() < 2) { broadcast(PREFIX + "Minimal 2 pemain."); return; }

        impostorCount = Math.max(1, Math.min(impostorCount, list.size() - 1));
        for (UUID u : players) { alive.put(u, true); impostor.put(u, false); }
        for (int i = 0; i < impostorCount; i++) {
            UUID pick = list.remove(rng.nextInt(list.size()));
            impostor.put(pick, true);
            Player p = Bukkit.getPlayer(pick);
            if (p != null) {
                p.sendTitle(ChatColor.RED + "IMPOSTOR", ChatColor.GRAY + "Bunuh semua tanpa ketahuan!", 10, 70, 20);
                p.playSound(p, Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 0.6f);
            }
        }
        for (UUID u : list) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                p.sendTitle(ChatColor.GREEN + "INNOCENT", ChatColor.AQUA + "Selesaikan 4 bos bersama!", 10, 70, 20);
                p.playSound(p, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            }
        }

        // urutan bos: ElderGuardian -> Warden -> Wither -> EnderDragon
        taskQueue.clear();
        taskQueue.add(new NamespacedKey(this, "t_elder"));
        taskQueue.add(new NamespacedKey(this, "t_warden"));
        taskQueue.add(new NamespacedKey(this, "t_wither"));
        taskQueue.add(new NamespacedKey(this, "t_dragon"));
        currentTask = taskQueue.poll();

        // kunci The End: portal frame & ender eye tidak dipakai — blokir via world access di PlayerPortalEvent
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            p.setGameMode(GameMode.SURVIVAL);
            p.getInventory().addItem(bellItem());
        }

        bossbar = Bukkit.createBossBar(taskTitle(), BarColor.YELLOW, BarStyle.SEGMENTED_10);
        bossbar.setProgress(0.0);
        players.forEach(u -> { Player p = Bukkit.getPlayer(u); if (p != null) bossbar.addPlayer(p); });

        phase = Phase.PLAYING;
        broadcast(PREFIX + "Game dimulai! " + ChatColor.YELLOW + "Bunuh: " + taskTitle());
    }

    String taskTitle() {
        return switch (bossName(currentTask)) {
            case "Elder Guardian" -> "Elder Guardian";
            case "Warden" -> "Warden";
            case "Wither" -> "Wither";
            default -> "Ender Dragon";
        };
    }

    String bossName(NamespacedKey key) {
        if (key == null) return "";
        return switch (key.getKey()) {
            case "t_elder" -> "Elder Guardian";
            case "t_warden" -> "Warden";
            case "t_wither" -> "Wither";
            default -> "Ender Dragon";
        };
    }

    void nextTaskOrWin() {
        broadcast(PREFIX + ChatColor.GREEN + "Bos " + bossName(currentTask) + " tumbang! Task " +
                (4 - taskQueue.size()) + "/4 selesai.");
        if (taskQueue.isEmpty()) { crewWin(); return; }
        currentTask = taskQueue.poll();
        bossbar.setTitle(taskTitle());
        bossbar.setProgress(0.0);
        broadcast(PREFIX + "Target berikutnya: " + ChatColor.YELLOW + taskTitle());
    }

    void crewWin() {
        phase = Phase.ENDED;
        if (bossbar != null) bossbar.removeAll();
        for (String n : getServer().getOnlinePlayers().stream().map(Player::getName).toList()) {
            Player p = getServer().getPlayer(n);
            if (p != null && players.contains(p.getUniqueId()))
                p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "CREW WIN", ChatColor.WHITE + "Semua task selesai!", 10, 100, 20);
        }
        broadcast(PREFIX + ChatColor.GREEN + "" + ChatColor.BOLD + "CREW WIN!");
        scheduleReset();
    }

    void traitorWin() {
        phase = Phase.ENDED;
        if (bossbar != null) bossbar.removeAll();
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p != null)
                p.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "TRAITOR WIN",
                        ChatColor.GRAY + "Impostor membantai semuanya…", 10, 100, 20);
        }
        broadcast(PREFIX + ChatColor.RED + "" + ChatColor.BOLD + "TRAITOR WIN!");
        scheduleReset();
    }

    void scheduleReset() {
        new BukkitRunnable() {
            @Override public void run() { reset(); }
        }.runTaskLater(this, 200L); // 10 detik
    }

    void reset() {
        phase = Phase.LOBBY;
        alive.clear(); impostor.clear(); preMeeting.clear();
        taskQueue.clear(); currentTask = null;
        vote = null;
        for (Location h : placedHeads) {
            if (h.getBlock().getType() == Material.PLAYER_HEAD) h.getBlock().setType(Material.AIR);
        }
        placedHeads.clear();
        broadcast(PREFIX + "Game direset. /sus add + /sus start untuk main lagi.");
    }

    /* ---------- meeting ---------- */

    boolean tryOpenMeeting(Player caller, boolean emergency) {
        if (phase != Phase.PLAYING) { caller.sendMessage(PREFIX + "Game belum jalan."); return false; }
        long now = getServer().getWorlds().getFirst().getGameTime();
        if (lastMeeting > 0 && now - lastMeeting < meetingCooldown * 20L) {
            long left = (meetingCooldown - (now - lastMeeting) / 20L);
            caller.sendMessage(PREFIX + "Meeting cooldown! Tunggu " + left + " dtk lagi.");
            return false;
        }
        openMeeting(caller, emergency ? "darurat!" : "menemukan mayat!");
        return true;
    }

    void openMeeting(Player caller, String reason) {
        phase = Phase.MEETING;
        lastMeeting = getServer().getWorlds().getFirst().getGameTime();

        Location room = meetingRoom != null ? meetingRoom : caller.getLocation().clone().add(0, 0, 0);
        preMeeting.clear();
        int i = 0;
        for (UUID u : new ArrayList<>(players)) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || !alive.getOrDefault(u, false)) continue;
            preMeeting.put(u, p.getLocation());
            double ang = Math.PI * 2 * i++ / 8.0;
            Location seat = room.clone().add(Math.cos(ang) * 3, 0, Math.sin(ang) * 3);
            seat.setYaw((float) Math.toDegrees(-ang));
            p.teleport(seat);
        }

        vote = new MeetingVote(this, caller.getName(), reason);
        vote.begin(meetingDuration);
    }

    void endMeeting(UUID ejected) {
        phase = Phase.PLAYING;
        // pulihkan posisi
        for (Map.Entry<UUID, Location> e : preMeeting.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.teleport(e.getValue());
        }
        preMeeting.clear();

        if (ejected == null) {
            broadcast(PREFIX + "Vote skip — tidak ada yang diterusir.");
            vote = null;
            checkWinConditions();
            return;
        }

        Player out = Bukkit.getPlayer(ejected);
        String name = out != null ? out.getName() : "?";
        boolean wasImp = impostor.getOrDefault(ejected, false);
        alive.put(ejected, false);
        broadcast(PREFIX + ChatColor.RED + name + ChatColor.GRAY + " diterusir! Dia "
                + (wasImp ? ChatColor.RED + "IMPOSTOR!" : ChatColor.GREEN + "INNOCENT…"));
        if (out != null) {
            out.setGameMode(GameMode.SPECTATOR);
            // kepala Steve di atas kepala yang diterusir
            Location headLoc = out.getLocation().getBlock().getLocation().add(0.5, 2, 0.5);
            if (headLoc.getBlock().getType().isAir()) {
                headLoc.getBlock().setType(Material.PLAYER_HEAD);
                placedHeads.add(headLoc);
            }
        }
        if (!wasImp) {
            // hukum kecil: innocent salah terusir → impostor makin bebas (tanpa efek tambahan)
        }
        vote = null;
        checkWinConditions();
    }

    void killPlayer(Player p) {
        UUID u = p.getUniqueId();
        if (!players.contains(u) || !alive.getOrDefault(u, false)) return;
        alive.put(u, false);
        p.setGameMode(GameMode.SPECTATOR);
        broadcast(PREFIX + ChatColor.RED + p.getName() + ChatColor.GRAY + " telah dibunuh!");
        checkWinConditions();
    }

    void checkWinConditions() {
        if (phase == Phase.ENDED) return;
        long aliveImp = 0, aliveCrew = 0;
        for (UUID u : players) {
            if (!alive.getOrDefault(u, false)) continue;
            if (impostor.getOrDefault(u, false)) aliveImp++; else aliveCrew++;
        }
        if (aliveImp == 0) { crewWin(); }
        else if (aliveCrew <= aliveImp) { traitorWin(); }
    }

    /* ---------- events ---------- */

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isBell(e.getItem())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!players.contains(p.getUniqueId())) return;
        tryOpenMeeting(p, true);
    }

    /** Bell tidak bisa dibuang/dipindah/dijatuhkan. */
    @EventHandler
    public void onBellDrop(PlayerDropItemEvent e) {
        if (isBell(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
        }
    }

    /** Bell tidak bisa dipindah ke chest/hopper/inventaris lain (klik, shift-klik, tukar slot). */
    @EventHandler
    public void onBellClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (isBell(e.getCurrentItem()) || isBell(e.getCursor())) e.setCancelled(true);
    }

    @EventHandler
    public void onBellDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
        for (ItemStack it : e.getNewItems().values()) {
            if (isBell(it)) { e.setCancelled(true); return; }
        }
    }

    @EventHandler
    public void onBossKill(EntityDeathEvent e) {
        if (phase != Phase.PLAYING || currentTask == null) return;
        EntityType t = e.getEntityType();
        String need = bossName(currentTask);
        boolean match = switch (need) {
            case "Elder Guardian" -> t == EntityType.ELDER_GUARDIAN && e.getEntity() instanceof ElderGuardian;
            case "Warden" -> t == EntityType.WARDEN && e.getEntity() instanceof Warden;
            case "Wither" -> t == EntityType.WITHER && e.getEntity() instanceof Wither;
            default -> t == EntityType.ENDER_DRAGON;
        };
        if (match && e.getEntity().getKiller() != null
                && players.contains(e.getEntity().getKiller().getUniqueId())) {
            nextTaskOrWin();
        }
    }

    /* ---------- util ---------- */

    void broadcast(String msg) { getServer().broadcastMessage(msg); }

    Scoreboard teamBoard() { return getServer().getScoreboardManager().getMainScoreboard(); }
}
